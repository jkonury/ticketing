package io.github.ticketing.e2e.support

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.payment.flow.PaymentFlowTests
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Import(PaymentFlowTests.TestClockConfig::class)
abstract class FlowSupport(
    val mvc: MockMvc,
    val jdbc: JdbcTemplate,
    val redis: StringRedisTemplate,
    val pg: PaymentFlowTests.TestPg,
    val clock: PaymentFlowTests.MutableClock,
    val collector: PaymentFlowTests.EventCollector,
) : IntegrationTestSupport() {

    private val json = JsonMapper.builder().build()
    data class Schedule(val id: Long, val seats: List<Long>)

    @BeforeEach fun resetFlowClock() {
        clock.now = Instant.now()
    }
    fun parse(result: MvcResult): Map<*, *> = json.readValue(result.response.contentAsString, Map::class.java)
    fun token(role: String = "USER"): String {
        val email = "${UUID.randomUUID()}@example.com"
        postJson("/api/v1/members", mapOf("email" to email, "password" to "password123", "name" to "홍길동", "role" to role)).also {
            assertThat(it.response.status).isEqualTo(201)
        }
        return parse(postJson("/api/v1/auth/tokens", mapOf("email" to email, "password" to "password123")))["accessToken"] as String
    }
    fun postJson(path: String, body: Any, token: String? = null): MvcResult {
        val request = post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
        if (token != null) request.header("Authorization", "Bearer $token").header("Idempotency-Key", UUID.randomUUID().toString())
        return mvc.perform(request).andReturn()
    }
    fun schedule(count: Int = 4): Schedule {
        val organizer = token("ORGANIZER")
        val venue = postJson(
            "/api/v1/venues",
            mapOf(
                "name" to "공연장",
                "seats" to (1..count).map { mapOf("section" to "A", "row" to "A", "number" to it, "grade" to "R") },
            ),
            organizer,
        )
        assertThat(venue.response.status).isEqualTo(201)
        val performance = postJson(
            "/api/v1/performances",
            mapOf(
                "title" to "공연",
                "description" to "공연 설명",
                "venueId" to parse(venue)["id"],
                "prices" to listOf(mapOf("grade" to "R", "price" to 100000)),
            ),
            organizer,
        )
        assertThat(performance.response.status).isEqualTo(201)
        val registered =
            postJson("/api/v1/performances/${parse(performance)["id"]}/schedules", mapOf("startsAt" to "2027-12-01T10:00:00Z"), organizer)
        assertThat(registered.response.status).isEqualTo(201)
        val id = (parse(registered)["id"] as Number).toLong()
        var seats: List<Long> = emptyList()
        eventually {
            seats =
                jdbc.queryForList(
                    "select seat_id from booking.schedule_seat where schedule_id = ? order by seat_id",
                    Long::class.java,
                    id,
                ).map {
                    requireNotNull(it)
                }
            assertThat(seats).hasSize(count)
        }
        return Schedule(id, seats)
    }
    fun book(token: String, schedule: Schedule, seats: List<Long>): MvcResult =
        postJson("/api/v1/bookings", mapOf("scheduleId" to schedule.id, "seatIds" to seats), token)
    fun created(result: MvcResult): Long {
        assertThat(result.response.status).isEqualTo(201)
        assertThat(parse(result)["status"]).isEqualTo("PENDING")
        return (parse(result)["bookingId"] as Number).toLong()
    }
    fun pay(token: String, id: Long) {
        mvc.perform(
            post(
                "/api/v1/bookings/$id/payments",
            ).header("Authorization", "Bearer $token").header("Idempotency-Key", UUID.randomUUID().toString()),
        ).andExpect(status().isAccepted)
        eventually {
            assertThat(pg.requests.any { it.orderId == id.toString() }).isTrue()
            assertThat(paymentStatus(id)).isEqualTo("REQUESTED")
        }
    }
    fun webhook(id: Long, type: String, amount: Int = 100000, eventId: String = UUID.randomUUID().toString()): MvcResult {
        val timestamp = clock.instant().epochSecond.toString()
        val failureReason = if (type == "PAYMENT_FAILED") "\"declined\"" else "null"
        val raw = """{"eventId":"$eventId","type":"$type","transactionId":"tx-$id","orderId":"$id","amount":$amount,""" +
            """"occurredAt":"${clock.instant()}","failureReason":$failureReason}"""
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("test-webhook-secret".toByteArray(), "HmacSHA256")) }
        val signature = HexFormat.of().formatHex(mac.doFinal("$timestamp.$raw".toByteArray()))
        return mvc.perform(
            post(
                "/api/v1/payments/webhooks/fake-pg",
            ).contentType(
                MediaType.APPLICATION_JSON,
            ).header("X-FakePg-Timestamp", timestamp).header("X-FakePg-Signature", signature).content(raw),
        ).andExpect(status().isOk).andReturn()
    }
    fun detail(token: String, id: Long): Map<*, *> =
        parse(mvc.perform(get("/api/v1/bookings/$id").header("Authorization", "Bearer $token")).andExpect(status().isOk).andReturn())
    fun awaitStatus(token: String, id: Long, state: String) = eventually { assertThat(detail(token, id)["status"]).isEqualTo(state) }
    fun paymentStatus(id: Long): String? =
        jdbc.queryForList("select status from payment.payment where order_id = ?", String::class.java, id).firstOrNull()
    fun reason(id: Long): String? =
        jdbc.queryForList("select cancel_reason from booking.booking where id = ?", String::class.java, id).firstOrNull()
    fun sold(schedule: Schedule): Int =
        requireNotNull(jdbc.queryForObject("select count(*) from booking.sold_seat where schedule_id = ?", Int::class.java, schedule.id))
    fun hold(schedule: Schedule, seat: Long) = "booking:hold:{${schedule.id}}:$seat"
    fun snapshot(schedule: Schedule) = "booking:seats:{${schedule.id}}"
    fun eventually(block: () -> Unit) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(block)
    }
}
