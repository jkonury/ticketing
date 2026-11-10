package io.github.ticketing.booking.adapter.provided.web

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter.Companion.holdKey
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@AutoConfigureMockMvc
class BookingApiIntegrationTests : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var register: RegisterScheduleSeatsUseCase

    @Autowired lateinit var redis: StringRedisTemplate

    private val json = JsonMapper.builder().build()

    private fun newSchedule(seatCount: Int = 6, title: String = "테스트 공연"): Long {
        val scheduleId = System.nanoTime()
        register.register(
            RegisterScheduleSeatsCommand(
                scheduleId, 1, title, 2, Instant.parse("2027-01-01T10:00:00Z"),
                (1..seatCount).map { RegisterScheduleSeatsCommand.SeatInfo(it.toLong(), "R", "A구역 ${it}번", BigDecimal(100000)) },
            ),
        )
        return scheduleId
    }

    private fun token(role: String = "USER"): String {
        val email = "u-${UUID.randomUUID()}@example.com"
        val body = { m: Map<String, String> -> json.writeValueAsString(m) }
        mvc.perform(
            post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("email" to email, "password" to "password123", "name" to "홍길동", "role" to role))),
        ).andExpect(status().isCreated)
        val res = mvc.perform(
            post("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("email" to email, "password" to "password123"))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return json.readValue(res, Map::class.java)["accessToken"] as String
    }

    private fun book(token: String, scheduleId: Long, seatIds: List<Long>, key: String = UUID.randomUUID().toString()): ResultActions =
        mvc.perform(
            post("/api/v1/bookings").header("Authorization", "Bearer $token").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("scheduleId" to scheduleId, "seatIds" to seatIds))),
        )

    private fun bookingId(result: ResultActions): Long =
        (json.readValue(result.andReturn().response.contentAsString, Map::class.java)["bookingId"] as Number).toLong()

    private fun auth(token: String) = "Bearer $token"

    @Test
    fun `예매를 만들면 201과 PENDING을 돌려주고 Redis에 선점이 생긴다`() {
        val schedule = newSchedule()
        val token = token()

        book(token, schedule, listOf(1, 2)).andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.totalAmount").value(200000))
            .andExpect(jsonPath("$.expiresAt").exists())
            .andExpect(jsonPath("$.seats.length()").value(2))
            .andExpect(jsonPath("$.seats[0].label").value("A구역 1번"))

        assertThat(redis.hasKey(holdKey(ScheduleId(schedule), SeatId(1)))).isTrue()
        assertThat(redis.hasKey(holdKey(ScheduleId(schedule), SeatId(2)))).isTrue()
    }

    @Test
    fun `같은 멱등 키로 다시 요청하면 200과 같은 bookingId를 돌려준다`() {
        val schedule = newSchedule()
        val token = token()
        val first = bookingId(book(token, schedule, listOf(1), "same-key").andExpect(status().isCreated))

        val second = book(token, schedule, listOf(1), "same-key").andExpect(status().isOk)

        assertThat(bookingId(second)).isEqualTo(first)
    }

    @Test
    fun `5매를 요청하면 400이다`() {
        val schedule = newSchedule()

        book(token(), schedule, listOf(1, 2, 3, 4, 5)).andExpect(status().isBadRequest)
    }

    @Test
    fun `멱등 키가 없거나 100자를 넘으면 400이다`() {
        val schedule = newSchedule()
        val token = token()

        mvc.perform(
            post("/api/v1/bookings").header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON)
                .content("""{"scheduleId":$schedule,"seatIds":[1]}"""),
        ).andExpect(status().isBadRequest)
        book(token, schedule, listOf(1), "k".repeat(101)).andExpect(status().isBadRequest)
    }

    @Test
    fun `다른 사용자가 같은 좌석을 요청하면 409와 좌석 목록을 돌려준다`() {
        val schedule = newSchedule()
        book(token(), schedule, listOf(1, 2)).andExpect(status().isCreated)

        book(token(), schedule, listOf(2, 3)).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SEAT_ALREADY_HELD"))
            .andExpect(jsonPath("$.seatIds[0]").value(2))
        assertThat(redis.hasKey(holdKey(ScheduleId(schedule), SeatId(3)))).isFalse()
    }

    @Test
    fun `없는 좌석은 404 SEAT_NOT_FOUND 없는 회차는 SCHEDULE_NOT_FOUND이다`() {
        val schedule = newSchedule()
        val token = token()

        book(token, schedule, listOf(1, 999)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("SEAT_NOT_FOUND"))
        book(token, System.nanoTime(), listOf(1)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"))
    }

    @Test
    fun `주최자 역할이나 토큰 없이 예매하면 403 401이다`() {
        val schedule = newSchedule()

        book(token("ORGANIZER"), schedule, listOf(1)).andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/bookings").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `내 예매 목록은 ID 내림차순 커서로 페이지를 나눈다`() {
        val token = token()
        val ids = (1..3).map { bookingId(book(token, newSchedule(title = "공연$it"), listOf(1)).andExpect(status().isCreated)) }
        book(token(), newSchedule(), listOf(1)).andExpect(status().isCreated)

        val page1 = mvc.perform(get("/api/v1/bookings/me?size=2").header("Authorization", auth(token)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].bookingId").value(ids[2]))
            .andExpect(jsonPath("$.items[0].performanceTitle").value("공연3"))
            .andExpect(jsonPath("$.items[0].seatCount").value(1))
            .andExpect(jsonPath("$.items[0].totalAmount").value(100000))
            .andExpect(jsonPath("$.nextCursor").value(ids[1]))
            .andReturn().response.contentAsString
        val cursor = (json.readValue(page1, Map::class.java)["nextCursor"] as Number).toLong()

        mvc.perform(get("/api/v1/bookings/me?size=2&cursor=$cursor").header("Authorization", auth(token)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].bookingId").value(ids[0]))
            .andExpect(jsonPath("$.nextCursor").doesNotExist())
    }

    @Test
    fun `내 예매 상세는 좌석 라벨을 포함하고 남의 예매는 404로 감춘다`() {
        val schedule = newSchedule()
        val owner = token()
        val id = bookingId(book(owner, schedule, listOf(3, 1)).andExpect(status().isCreated))

        mvc.perform(get("/api/v1/bookings/$id").header("Authorization", auth(owner)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.expiresAt").exists())
            .andExpect(jsonPath("$.seats[0].label").value("A구역 1번"))
            .andExpect(jsonPath("$.seats[1].label").value("A구역 3번"))
        mvc.perform(get("/api/v1/bookings/$id").header("Authorization", auth(token())))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"))
    }

    @Test
    fun `PENDING 예매를 취소하면 선점이 풀려 같은 좌석을 다시 선점할 수 있고 재취소는 같은 결과다`() {
        val schedule = newSchedule()
        val owner = token()
        val id = bookingId(book(owner, schedule, listOf(1, 2)).andExpect(status().isCreated))

        val cancel = { mvc.perform(post("/api/v1/bookings/$id/cancel").header("Authorization", auth(owner))) }
        cancel().andExpect(status().isOk).andExpect(jsonPath("$.status").value("CANCELLED"))
        cancel().andExpect(status().isOk).andExpect(jsonPath("$.status").value("CANCELLED"))

        assertThat(redis.hasKey(holdKey(ScheduleId(schedule), SeatId(1)))).isFalse()
        book(token(), schedule, listOf(1, 2)).andExpect(status().isCreated)
    }

    @Test
    fun `남의 예매를 취소하면 403이다`() {
        val schedule = newSchedule()
        val id = bookingId(book(token(), schedule, listOf(1)).andExpect(status().isCreated))

        mvc.perform(post("/api/v1/bookings/$id/cancel").header("Authorization", auth(token())))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("NOT_BOOKING_OWNER"))
    }
}
