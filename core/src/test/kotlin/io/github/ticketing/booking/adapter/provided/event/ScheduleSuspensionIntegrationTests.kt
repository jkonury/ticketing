package io.github.ticketing.booking.adapter.provided.event

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter.Companion.holdKey
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import io.github.ticketing.catalog.PerformanceSuspensionChanged
import io.github.ticketing.catalog.ScheduleRegistered
import io.github.ticketing.catalog.ScheduleSeat
import io.github.ticketing.member.domain.Member
import io.github.ticketing.member.domain.Role
import io.github.ticketing.member.security.JwtTokenProvider
import io.github.ticketing.publicationCompleted
import io.github.ticketing.publicationIds
import io.github.ticketing.support.KafkaPaymentStub
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ScheduleSuspensionIntegrationTests(
    private val mvc: MockMvcTester,
    private val publisher: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
    private val jdbc: JdbcTemplate,
    private val tokens: JwtTokenProvider,
    private val payments: KafkaPaymentStub,
    private val redis: StringRedisTemplate,
) : IntegrationTestSupport() {

    private val amount = BigDecimal(100_000)
    private val performanceId = System.nanoTime()
    private val token = run {
        val member = Member("test@example.com", "unused", "이름", Role.USER, Instant.now()).apply { id = performanceId }
        "Bearer ${tokens.issueAccessToken(member).value}"
    }
    private val json = JsonMapper.builder().build()

    @Test
    fun `공연 중지 후 선점과 신규 예매를 막고 기존 예매의 결제와 취소는 가능하다`() {
        val scheduleId = schedule()
        val key = UUID.randomUUID().toString()
        val booking = book(scheduleId, 1, key).exchange()
        assertThat(booking).hasStatus(HttpStatus.CREATED)
        val bookingId = json.readTree(booking.response.contentAsString)["bookingId"].asLong()
        val suspended = state(false, 1)
        deliver(suspended)
        assertThat(book(scheduleId, 2)).hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("PERFORMANCE_SUSPENDED")
        assertThat(redis.hasKey(holdKey(ScheduleId(scheduleId), SeatId(2)))).isFalse()
        assertThat(book(scheduleId, 1, key)).hasStatus(HttpStatus.OK)
            .bodyJson().extractingPath("$.bookingId").convertTo(Long::class.java).isEqualTo(bookingId)

        assertThat(
            mvc.post().uri("/api/v1/bookings/$bookingId/payments").header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString()),
        ).hasStatus(HttpStatus.ACCEPTED)
        payments.awaitPaymentRequested(bookingId)
        payments.publishSucceeded(bookingId, amount)
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(mvc.get().uri("/api/v1/bookings/$bookingId").header("Authorization", token))
                .hasStatus(HttpStatus.OK).bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED")
        }
        assertThat(mvc.post().uri("/api/v1/bookings/$bookingId/cancel").header("Authorization", token))
            .hasStatus(HttpStatus.OK).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED")

        deliver(state(true, 2))
        deliver(suspended)
        assertThat(book(scheduleId, 2)).hasStatus(HttpStatus.CREATED)
    }

    @Test
    fun `중지 이벤트가 먼저 도착해도 이후 등록된 회차의 판매를 막는다`() {
        val suspended = state(false, 1)
        deliver(suspended)
        val scheduleId = schedule()
        deliver(suspended)
        assertThat(book(scheduleId, 1)).hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("PERFORMANCE_SUSPENDED")

        deliver(state(true, 2))
        deliver(suspended)
        val laterScheduleId = schedule()
        assertThat(book(laterScheduleId, 1)).hasStatus(HttpStatus.CREATED)
    }

    private fun schedule(): Long {
        val id = System.nanoTime()
        val event = ScheduleRegistered(
            id,
            performanceId,
            "공연",
            20,
            Instant.parse("2030-12-01T10:00:00Z"),
            (1L..2L).map { ScheduleSeat(it, "VIP", "A-$it", amount) },
        )
        tx.executeWithoutResult { publisher.publishEvent(event) }
        waitFor(event.javaClass, "scheduleId", id, "ScheduleRegisteredListener.on", emptySet())
        return id
    }

    private fun state(enabled: Boolean, version: Long) =
        PerformanceSuspensionChanged(UUID.randomUUID(), 17, performanceId, enabled, version, if (enabled) null else "점검", Instant.now())

    private fun deliver(event: PerformanceSuspensionChanged) {
        val listener = "ScheduleRegisteredListener.on"
        val existing = publicationIds(jdbc, event.javaClass, "performanceId", performanceId, listener).toSet()
        tx.executeWithoutResult { publisher.publishEvent(event) }
        waitFor(event.javaClass, "performanceId", performanceId, listener, existing)
    }

    private fun waitFor(eventType: Class<*>, field: String, key: Long, listener: String, existing: Set<UUID>) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val delivered = publicationIds(jdbc, eventType, field, key, listener) - existing
            assertThat(delivered).hasSize(1)
            assertThat(publicationCompleted(jdbc, delivered.single())).isTrue()
        }
    }

    private fun book(scheduleId: Long, seatId: Long, key: String = UUID.randomUUID().toString()) =
        mvc.post().uri("/api/v1/bookings").header("Authorization", token).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content("""{"scheduleId":$scheduleId,"seatIds":[$seatId]}""")
}
