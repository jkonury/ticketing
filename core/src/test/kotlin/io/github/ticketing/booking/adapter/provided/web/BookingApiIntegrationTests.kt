package io.github.ticketing.booking.adapter.provided.web

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter.Companion.holdKey
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import io.github.ticketing.expectStatus
import io.github.ticketing.payment.adapter.provided.event.PaymentRequestListener
import io.github.ticketing.payment.adapter.provided.event.RefundRequestListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class BookingApiIntegrationTests(
    private val mockMvcTester: MockMvcTester,
    private val register: RegisterScheduleSeatsUseCase,
    private val redis: StringRedisTemplate,
) : IntegrationTestSupport() {
    private val json = JsonMapper.builder().build()

    private fun newSchedule(seatCount: Int = 6, title: String = "테스트 공연"): Long {
        val scheduleId = System.nanoTime()
        register.register(
            RegisterScheduleSeatsCommand(
                scheduleId,
                1,
                title,
                2,
                Instant.parse("2027-01-01T10:00:00Z"),
                (1..seatCount).map { RegisterScheduleSeatsCommand.SeatInfo(it.toLong(), "R", "A구역 ${it}번", BigDecimal(100000)) },
            ),
        )
        return scheduleId
    }

    private fun token(role: String = "USER"): String {
        val email = "u-${UUID.randomUUID()}@example.com"
        val body = { m: Map<String, String> -> json.writeValueAsString(m) }
        assertThat(
            mockMvcTester.post().uri("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("email" to email, "password" to "password123", "name" to "홍길동", "role" to role)))
                .exchange(),
        ).hasStatus(HttpStatus.CREATED)
        val res = mockMvcTester.post().uri("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("email" to email, "password" to "password123")))
            .exchange()
        assertThat(res).hasStatus(HttpStatus.OK)
        return json.readValue(res.response.contentAsString, Map::class.java)["accessToken"] as String
    }

    private fun book(token: String, scheduleId: Long, seatIds: List<Long>, key: String = UUID.randomUUID().toString()): MvcTestResult =
        mockMvcTester.post().uri("/api/v1/bookings").header("Authorization", "Bearer $token").header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(mapOf("scheduleId" to scheduleId, "seatIds" to seatIds)))
            .exchange()

    private fun bookingId(result: MvcTestResult): Long =
        (json.readValue(result.response.contentAsString, Map::class.java)["bookingId"] as Number).toLong()

    private fun auth(token: String) = "Bearer $token"

    @Test
    fun `예매를 만들면 201과 PENDING을 돌려주고 Redis에 선점이 생긴다`() {
        val schedule = newSchedule()
        val token = token()

        assertThat(book(token, schedule, listOf(1, 2)))
            .hasStatus(HttpStatus.CREATED)
            .bodyJson().apply {
                extractingPath("$.status").isEqualTo("PENDING")
                extractingPath("$.totalAmount").isEqualTo(200000)
                hasPath("$.expiresAt")
                extractingPath("$.seats.length()").isEqualTo(2)
                extractingPath("$.seats[0].label").isEqualTo("A구역 1번")
            }

        assertThat(redis.hasKey(holdKey(ScheduleId(schedule), SeatId(1)))).isTrue()
        assertThat(redis.hasKey(holdKey(ScheduleId(schedule), SeatId(2)))).isTrue()
    }

    @Test
    fun `같은 멱등 키로 다시 요청하면 200과 같은 bookingId를 돌려준다`() {
        val schedule = newSchedule()
        val token = token()
        val first = bookingId(book(token, schedule, listOf(1), "same-key").expectStatus(HttpStatus.CREATED))

        val second = book(token, schedule, listOf(1), "same-key")
        assertThat(second).hasStatus(HttpStatus.OK)

        assertThat(bookingId(second)).isEqualTo(first)
    }

    @Test
    fun `5매를 요청하면 400이다`() {
        val schedule = newSchedule()

        assertThat(book(token(), schedule, listOf(1, 2, 3, 4, 5))).hasStatus(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `멱등 키가 없거나 100자를 넘으면 400이다`() {
        val schedule = newSchedule()
        val token = token()

        mockMvcTester.post().uri("/api/v1/bookings").header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON)
            .content("""{"scheduleId":$schedule,"seatIds":[1]}""")
            .assertThat()
            .hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(book(token, schedule, listOf(1), "k".repeat(101))).hasStatus(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `다른 사용자가 같은 좌석을 요청하면 409와 좌석 목록을 돌려준다`() {
        val schedule = newSchedule()
        book(token(), schedule, listOf(1, 2)).expectStatus(HttpStatus.CREATED)

        assertThat(book(token(), schedule, listOf(2, 3)))
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson().apply {
                extractingPath("$.code").isEqualTo("SEAT_ALREADY_HELD")
                extractingPath("$.seatIds[0]").isEqualTo(2)
            }
        assertThat(redis.hasKey(holdKey(ScheduleId(schedule), SeatId(3)))).isFalse()
    }

    @Test
    fun `없는 좌석은 404 SEAT_NOT_FOUND 없는 회차는 SCHEDULE_NOT_FOUND이다`() {
        val schedule = newSchedule()
        val token = token()

        assertThat(book(token, schedule, listOf(1, 999)))
            .hasStatus(HttpStatus.NOT_FOUND)
            .bodyJson().extractingPath("$.code").isEqualTo("SEAT_NOT_FOUND")
        assertThat(book(token, System.nanoTime(), listOf(1)))
            .hasStatus(HttpStatus.NOT_FOUND)
            .bodyJson().extractingPath("$.code").isEqualTo("SCHEDULE_NOT_FOUND")
    }

    @Test
    fun `주최자 역할이나 토큰 없이 예매하면 403 401이다`() {
        val schedule = newSchedule()

        assertThat(book(token("ORGANIZER"), schedule, listOf(1))).hasStatus(HttpStatus.FORBIDDEN)
        mockMvcTester.post().uri("/api/v1/bookings").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON).content("{}")
            .assertThat()
            .hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `내 예매 목록은 ID 내림차순 커서로 페이지를 나눈다`() {
        val token = token()
        val ids = (1..3).map { bookingId(book(token, newSchedule(title = "공연$it"), listOf(1)).expectStatus(HttpStatus.CREATED)) }
        book(token(), newSchedule(), listOf(1)).expectStatus(HttpStatus.CREATED)

        val page1 = mockMvcTester.get().uri("/api/v1/bookings/me?size=2").header("Authorization", auth(token)).exchange()
        assertThat(page1)
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.items.length()").isEqualTo(2)
                extractingPath("$.items[0].bookingId").convertTo(Long::class.java).isEqualTo(ids[2])
                extractingPath("$.items[0].performanceTitle").isEqualTo("공연3")
                extractingPath("$.items[0].seatCount").isEqualTo(1)
                extractingPath("$.items[0].totalAmount").isEqualTo(100000)
                extractingPath("$.nextCursor").convertTo(Long::class.java).isEqualTo(ids[1])
            }
        val cursor = (json.readValue(page1.response.contentAsString, Map::class.java)["nextCursor"] as Number).toLong()

        assertThat(mockMvcTester.get().uri("/api/v1/bookings/me?size=2&cursor=$cursor").header("Authorization", auth(token)))
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.items.length()").isEqualTo(1)
                extractingPath("$.items[0].bookingId").convertTo(Long::class.java).isEqualTo(ids[0])
                extractingPath("$.nextCursor").isNull()
            }
    }

    @Test
    fun `내 예매 상세는 좌석 라벨을 포함하고 남의 예매는 404로 감춘다`() {
        val schedule = newSchedule()
        val owner = token()
        val id = bookingId(book(owner, schedule, listOf(3, 1)).expectStatus(HttpStatus.CREATED))

        assertThat(mockMvcTester.get().uri("/api/v1/bookings/$id").header("Authorization", auth(owner)))
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.status").isEqualTo("PENDING")
                hasPath("$.expiresAt")
                extractingPath("$.seats[0].label").isEqualTo("A구역 1번")
                extractingPath("$.seats[1].label").isEqualTo("A구역 3번")
            }
        assertThat(mockMvcTester.get().uri("/api/v1/bookings/$id").header("Authorization", auth(token())))
            .hasStatus(HttpStatus.NOT_FOUND)
            .bodyJson().extractingPath("$.code").isEqualTo("BOOKING_NOT_FOUND")
    }

    @Test
    fun `PENDING 예매를 취소하면 선점이 풀려 같은 좌석을 다시 선점할 수 있고 재취소는 같은 결과다`() {
        val schedule = newSchedule()
        val owner = token()
        val id = bookingId(book(owner, schedule, listOf(1, 2)).expectStatus(HttpStatus.CREATED))

        val cancel = {
            mockMvcTester.post().uri("/api/v1/bookings/$id/cancel").header("Authorization", auth(owner)).exchange()
        }

        assertThat(cancel()).hasStatus(HttpStatus.OK).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED")
        assertThat(cancel()).hasStatus(HttpStatus.OK).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED")

        assertThat(redis.hasKey(holdKey(ScheduleId(schedule), SeatId(1)))).isFalse()
        assertThat(book(token(), schedule, listOf(1, 2))).hasStatus(HttpStatus.CREATED)
    }

    @Test
    fun `남의 예매를 취소하면 403이다`() {
        val schedule = newSchedule()
        val id = bookingId(book(token(), schedule, listOf(1)).expectStatus(HttpStatus.CREATED))

        assertThat(mockMvcTester.post().uri("/api/v1/bookings/$id/cancel").header("Authorization", auth(token())).exchange())
            .hasStatus(HttpStatus.FORBIDDEN)
            .bodyJson().extractingPath("$.code").isEqualTo("NOT_BOOKING_OWNER")
    }
}
