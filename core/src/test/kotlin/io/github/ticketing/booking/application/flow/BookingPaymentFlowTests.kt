package io.github.ticketing.booking.application.flow

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
import io.github.ticketing.booking.BookingExpired
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter.Companion.holdKey
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter.Companion.snapshotKey
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import io.github.ticketing.payment.PaymentFailed
import io.github.ticketing.payment.PaymentRequested
import io.github.ticketing.payment.PaymentSucceeded
import io.github.ticketing.payment.RefundRequested
import io.github.ticketing.payment.adapter.provided.event.PaymentRequestListener
import io.github.ticketing.payment.adapter.provided.event.RefundRequestListener
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@AutoConfigureMockMvc
@Import(BookingPaymentFlowTests.TestClockConfig::class)
class BookingPaymentFlowTests(
    private val mvc: MockMvc,
    private val register: RegisterScheduleSeatsUseCase,
    private val redis: StringRedisTemplate,
    private val jdbc: JdbcTemplate,
    private val publisher: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
    private val clock: MutableClock,
    private val collector: EventCollector,
) : IntegrationTestSupport() {

    @MockitoBean
    lateinit var paymentRequestListener: PaymentRequestListener

    @MockitoBean
    lateinit var refundRequestListener: RefundRequestListener

    class MutableClock : Clock() {
        @Volatile var now: Instant = Instant.now()
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    @Component
    class EventCollector {
        val events = CopyOnWriteArrayList<Any>()

        @EventListener
        fun on(event: Any) {
            if (event.javaClass.name.startsWith("io.github.ticketing.booking.Booking") ||
                event.javaClass.name.startsWith("io.github.ticketing.payment.")
            ) {
                events += event
            }
        }

        fun <T : Any> of(type: Class<T>, predicate: (T) -> Boolean): List<T> = events.filterIsInstance(type).filter(predicate)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestClockConfig {

        @Bean
        @Primary
        fun mutableClock() = MutableClock()

        @Bean
        fun eventCollector() = EventCollector()
    }

    private val json = JsonMapper.builder().build()

    @BeforeEach
    fun resetClock() {
        clock.now = Instant.now()
    }

    private fun newSchedule(seatCount: Int = 4): Long {
        val scheduleId = System.nanoTime()
        register.register(
            RegisterScheduleSeatsCommand(
                scheduleId,
                1,
                "결제 흐름 공연",
                2,
                Instant.parse("2027-01-01T10:00:00Z"),
                (1..seatCount).map { RegisterScheduleSeatsCommand.SeatInfo(it.toLong(), "R", "A구역 ${it}번", BigDecimal(100000)) },
            ),
        )
        return scheduleId
    }

    private fun token(): String {
        val email = "u-${UUID.randomUUID()}@example.com"
        val body = { m: Map<String, String> -> json.writeValueAsString(m) }
        mvc.perform(
            post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("email" to email, "password" to "password123", "name" to "홍길동", "role" to "USER"))),
        ).andExpect(status().isCreated)
        val res = mvc.perform(
            post("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("email" to email, "password" to "password123"))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return json.readValue(res, Map::class.java)["accessToken"] as String
    }

    private fun book(token: String, scheduleId: Long, seatIds: List<Long>): Long {
        val res = mvc.perform(
            post("/api/v1/bookings").header("Authorization", "Bearer $token").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("scheduleId" to scheduleId, "seatIds" to seatIds))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return (json.readValue(res, Map::class.java)["bookingId"] as Number).toLong()
    }

    private fun pay(token: String, bookingId: Long, key: String = UUID.randomUUID().toString()): ResultActions =
        mvc.perform(post("/api/v1/bookings/$bookingId/payments").header("Authorization", "Bearer $token").header("Idempotency-Key", key))

    private fun cancel(token: String, bookingId: Long): ResultActions =
        mvc.perform(post("/api/v1/bookings/$bookingId/cancel").header("Authorization", "Bearer $token"))

    private fun statusOf(token: String, bookingId: Long): String {
        val res = mvc.perform(get("/api/v1/bookings/$bookingId").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return json.readValue(res, Map::class.java)["status"] as String
    }

    private fun awaitStatus(token: String, bookingId: Long, expected: String) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted { assertThat(statusOf(token, bookingId)).isEqualTo(expected) }
    }

    private fun succeed(bookingId: Long, paymentId: Long = bookingId, amount: BigDecimal = BigDecimal(100000)) {
        tx.executeWithoutResult {
            publisher.publishEvent(PaymentSucceeded(paymentId, bookingId, amount, Instant.now()))
        }
    }

    private fun snapshot(scheduleId: Long, seatId: Long): String? =
        redis.opsForHash<String, String>().get(snapshotKey(ScheduleId(scheduleId)), seatId.toString())

    private fun soldSeatCount(bookingId: Long): Int =
        requireNotNull(jdbc.queryForObject("SELECT count(*) FROM booking.sold_seat WHERE booking_id = ?", Int::class.java, bookingId))

    @Test
    fun `결제를 요청하면 PaymentRequested가 한 번 발행되고 재요청해도 늘지 않는다`() {
        val schedule = newSchedule()
        val token = token()
        val id = book(token, schedule, listOf(1, 2))

        pay(
            token,
            id,
        ).andExpect(status().isAccepted).andExpect(jsonPath("$.bookingId").value(id)).andExpect(jsonPath("$.status").value("PENDING"))
        pay(token, id).andExpect(status().isAccepted).andExpect(jsonPath("$.status").value("PENDING"))

        val requested = collector.of(PaymentRequested::class.java) { it.orderId == id }

        assertThat(requested).hasSize(1)
        assertThat(requested[0].idempotencyKey).isEqualTo("booking-$id")
        assertThat(requested[0].amount).isEqualByComparingTo(BigDecimal(200000))
    }

    @Test
    fun `멱등 키 헤더가 없으면 400이고 남의 예매는 403이다`() {
        val schedule = newSchedule()
        val owner = token()
        val id = book(owner, schedule, listOf(1))

        mvc.perform(post("/api/v1/bookings/$id/payments").header("Authorization", "Bearer $owner")).andExpect(status().isBadRequest)

        pay(token(), id).andExpect(status().isForbidden)
    }

    @Test
    fun `만료된 예매의 결제 요청은 409 BOOKING_EXPIRED이고 EXPIRED로 저장되며 좌석을 다시 선점할 수 있다`() {
        val schedule = newSchedule()
        val token = token()
        val id = book(token, schedule, listOf(1))
        clock.now = clock.now.plus(Duration.ofMinutes(6))

        pay(token, id).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("BOOKING_EXPIRED"))

        assertThat(statusOf(token, id)).isEqualTo("EXPIRED")
        assertThat(collector.of(BookingExpired::class.java) { it.bookingId == id }).hasSize(1)
        assertThat(snapshot(schedule, 1)).isEqualTo("AVAILABLE")
        assertThat(collector.of(PaymentRequested::class.java) { it.orderId == id }).isEmpty()
    }

    @Test
    fun `결제가 성공하면 CONFIRMED와 sold_seat와 Redis SOLD가 생기고 같은 성공이 다시 와도 같다`() {
        val schedule = newSchedule()
        val token = token()
        val id = book(token, schedule, listOf(1, 2))
        pay(token, id).andExpect(status().isAccepted)

        succeed(id, amount = BigDecimal(200000))
        awaitStatus(token, id, "CONFIRMED")
        succeed(id, amount = BigDecimal(200000))
        succeed(id, paymentId = 999, amount = BigDecimal(200000))

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(snapshot(schedule, 1)).isEqualTo("SOLD")
            assertThat(snapshot(schedule, 2)).isEqualTo("SOLD")
        }
        assertThat(soldSeatCount(id)).isEqualTo(2)

        // 중복 이벤트가 모두 처리될 시간을 준다
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(collector.of(BookingConfirmed::class.java) { it.bookingId == id }).hasSize(1)
            assertThat(statusOf(token, id)).isEqualTo("CONFIRMED")
        }

        assertThat(soldSeatCount(id)).isEqualTo(2)
        assertThat(collector.of(BookingConfirmed::class.java) { it.bookingId == id }.single().seatIds).containsExactlyInAnyOrder(1L, 2L)
    }

    @Test
    fun `결제가 실패하면 CANCELLED가 되고 좌석을 다시 선점할 수 있다`() {
        val schedule = newSchedule()
        val token = token()
        val id = book(token, schedule, listOf(1))
        pay(token, id).andExpect(status().isAccepted)

        tx.executeWithoutResult { publisher.publishEvent(PaymentFailed(1, id, "카드 한도 초과", Instant.now())) }

        awaitStatus(token, id, "CANCELLED")
        await().atMost(Duration.ofSeconds(10)).untilAsserted { assertThat(snapshot(schedule, 1)).isEqualTo("AVAILABLE") }

        val cancelled = collector.of(BookingCancelled::class.java) { it.bookingId == id }.single()

        assertThat(cancelled.reason).isEqualTo("PAYMENT_FAILED")
        assertThat(cancelled.refundRequired).isFalse()

        book(token(), schedule, listOf(1))
    }

    @Test
    fun `만료 후 결제 성공이 오면 EXPIRED가 되고 RefundRequested가 발행된다`() {
        val schedule = newSchedule()
        val token = token()
        val id = book(token, schedule, listOf(1))
        pay(token, id).andExpect(status().isAccepted)
        clock.now = clock.now.plus(Duration.ofMinutes(6))

        succeed(id)

        awaitStatus(token, id, "EXPIRED")
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(collector.of(RefundRequested::class.java) { it.orderId == id }).hasSize(1)
        }
        assertThat(collector.of(BookingExpired::class.java) { it.bookingId == id }).hasSize(1)
        assertThat(soldSeatCount(id)).isZero()
        assertThat(collector.of(BookingConfirmed::class.java) { it.bookingId == id }).isEmpty()
    }

    @Test
    fun `다른 예매가 이미 sold_seat를 가진 좌석이면 CANCELLED SEAT_ALREADY_SOLD와 RefundRequested가 생긴다`() {
        val schedule = newSchedule()
        val tokenA = token()
        val tokenB = token()
        val bookingA = book(tokenA, schedule, listOf(1, 2))

        // A의 선점 키가 사라진 상황(TTL 누락·유실)을 흉내 내 같은 좌석을 B가 선점하게 한다
        redis.delete(listOf(holdKey(ScheduleId(schedule), SeatId(1)), holdKey(ScheduleId(schedule), SeatId(2))))
        val bookingB = book(tokenB, schedule, listOf(2, 3))

        pay(tokenA, bookingA).andExpect(status().isAccepted)
        pay(tokenB, bookingB).andExpect(status().isAccepted)
        succeed(bookingB, amount = BigDecimal(200000))
        awaitStatus(tokenB, bookingB, "CONFIRMED")

        succeed(bookingA, amount = BigDecimal(200000))

        awaitStatus(tokenA, bookingA, "CANCELLED")
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(collector.of(RefundRequested::class.java) { it.orderId == bookingA }).hasSize(1)
        }
        assertThat(collector.of(RefundRequested::class.java) { it.orderId == bookingA }.single().reason).isEqualTo("SEAT_ALREADY_SOLD")
        assertThat(collector.of(BookingCancelled::class.java) { it.bookingId == bookingA }.single().reason).isEqualTo("SEAT_ALREADY_SOLD")

        // 좌석 1은 들어갔다가 같은 트랜잭션에서 지워져 A의 sold_seat는 남지 않고 B의 것만 있다
        assertThat(soldSeatCount(bookingA)).isZero()
        assertThat(soldSeatCount(bookingB)).isEqualTo(2)
        assertThat(snapshot(schedule, 2)).isEqualTo("SOLD")
    }

    @Test
    fun `결제 금액이 예매 금액과 다르면 CANCELLED AMOUNT_MISMATCH와 RefundRequested가 생기고 좌석이 반환된다`() {
        val schedule = newSchedule()
        val token = token()
        val id = book(token, schedule, listOf(1, 2))
        pay(token, id).andExpect(status().isAccepted)

        succeed(id, amount = BigDecimal(100000))

        awaitStatus(token, id, "CANCELLED")
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(collector.of(RefundRequested::class.java) { it.orderId == id }).hasSize(1)
            assertThat(snapshot(schedule, 1)).isEqualTo("AVAILABLE")
            assertThat(snapshot(schedule, 2)).isEqualTo("AVAILABLE")
        }

        assertThat(collector.of(RefundRequested::class.java) { it.orderId == id }.single().reason).isEqualTo("AMOUNT_MISMATCH")

        val cancelled = collector.of(BookingCancelled::class.java) { it.bookingId == id }.single()

        assertThat(cancelled.reason).isEqualTo("AMOUNT_MISMATCH")
        assertThat(cancelled.refundRequired).isTrue()
        assertThat(soldSeatCount(id)).isZero()
        assertThat(collector.of(BookingConfirmed::class.java) { it.bookingId == id }).isEmpty()
        assertThat(
            jdbc.queryForObject("SELECT cancel_reason FROM booking.booking WHERE id = ?", String::class.java, id),
        ).isEqualTo("AMOUNT_MISMATCH")

        book(token(), schedule, listOf(1, 2))
    }

    @Test
    fun `확정된 예매에 결제를 다시 요청하면 409 INVALID_BOOKING_STATE이다`() {
        val schedule = newSchedule()
        val token = token()
        val id = book(token, schedule, listOf(1))
        pay(token, id).andExpect(status().isAccepted)
        succeed(id)
        awaitStatus(token, id, "CONFIRMED")

        pay(token, id).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("INVALID_BOOKING_STATE"))

        assertThat(collector.of(PaymentRequested::class.java) { it.orderId == id }).hasSize(1)
    }

    @Test
    fun `취소된 예매에 결제를 다시 요청하면 409 INVALID_BOOKING_STATE이다`() {
        val schedule = newSchedule()
        val token = token()
        val id = book(token, schedule, listOf(1))
        pay(token, id).andExpect(status().isAccepted)
        cancel(token, id).andExpect(status().isOk)

        pay(token, id).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("INVALID_BOOKING_STATE"))
    }

    @Test
    fun `이미 결제를 요청한 PENDING은 만료 시각이 지나도 재요청에 202를 준다`() {
        val schedule = newSchedule()
        val token = token()
        val id = book(token, schedule, listOf(1))
        pay(token, id).andExpect(status().isAccepted)
        clock.now = clock.now.plus(Duration.ofMinutes(6))

        pay(token, id).andExpect(status().isAccepted).andExpect(jsonPath("$.status").value("PENDING"))

        assertThat(collector.of(PaymentRequested::class.java) { it.orderId == id }).hasSize(1)
    }

    @Test
    fun `확정된 예매를 취소하면 sold_seat가 삭제되고 RefundRequested가 발행되며 Redis가 AVAILABLE이 된다`() {
        val schedule = newSchedule()
        val token = token()
        val id = book(token, schedule, listOf(1, 2))
        pay(token, id).andExpect(status().isAccepted)
        succeed(id, amount = BigDecimal(200000))
        awaitStatus(token, id, "CONFIRMED")
        await().atMost(Duration.ofSeconds(10)).untilAsserted { assertThat(snapshot(schedule, 1)).isEqualTo("SOLD") }

        cancel(token, id).andExpect(status().isOk).andExpect(jsonPath("$.status").value("CANCELLED"))

        assertThat(soldSeatCount(id)).isZero()
        assertThat(snapshot(schedule, 1)).isEqualTo("AVAILABLE")
        assertThat(snapshot(schedule, 2)).isEqualTo("AVAILABLE")
        assertThat(collector.of(RefundRequested::class.java) { it.orderId == id }).hasSize(1)
        assertThat(collector.of(BookingCancelled::class.java) { it.bookingId == id }.single().refundRequired).isTrue()

        cancel(token, id).andExpect(status().isOk)

        assertThat(collector.of(RefundRequested::class.java) { it.orderId == id }).hasSize(1)
    }
}
