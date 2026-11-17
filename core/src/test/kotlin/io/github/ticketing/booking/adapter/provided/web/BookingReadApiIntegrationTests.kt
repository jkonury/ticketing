package io.github.ticketing.booking.adapter.provided.web

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.MutableClock
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import org.hamcrest.Matchers.hasSize
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import ticketing.jooq.booking.tables.references.BOOKING_SEAT
import ticketing.jooq.booking.tables.references.SOLD_SEAT
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import ticketing.jooq.booking.tables.references.BOOKING_ as BOOKING

class BookingReadApiIntegrationTests(
    private val mvc: MockMvc,
    private val dsl: DSLContext,
    private val clock: MutableClock,
    private val register: RegisterScheduleSeatsUseCase,
) : IntegrationTestSupport() {
    private val json = JsonMapper.builder().build()
    private val now = Instant.now()

    @BeforeEach
    fun fixClock() {
        clock.now = now
    }

    private fun member(role: String = "USER"): Pair<Long, String> {
        val email = "booking-read-${UUID.randomUUID()}@example.com"
        val signup = mvc.perform(
            post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123", "name" to "회원", "role" to role))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val login = mvc.perform(
            post("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123"))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return (json.readValue(signup, Map::class.java)["id"] as Number).toLong() to
            (json.readValue(login, Map::class.java)["accessToken"] as String)
    }

    private fun schedule(performanceId: Long, organizerId: Long): Long {
        val id = System.nanoTime()
        register.register(
            RegisterScheduleSeatsCommand(
                id,
                performanceId,
                "공연",
                organizerId,
                now.plusSeconds(86400),
                (1L..6L).map { RegisterScheduleSeatsCommand.SeatInfo(it, "R", "좌석 $it", BigDecimal(100)) },
            ),
        )
        return id
    }

    private fun booking(memberId: Long, scheduleId: Long, seat: Long, state: String, expiry: Instant, payment: Boolean = false): Long {
        val record = dsl.insertInto(BOOKING)
            .set(BOOKING.MEMBER_ID, memberId).set(BOOKING.SCHEDULE_ID, scheduleId).set(BOOKING.STATUS, state)
            .set(BOOKING.TOTAL_AMOUNT, 100L).set(BOOKING.HOLD_TOKEN, UUID.randomUUID().toString())
            .set(BOOKING.IDEMPOTENCY_KEY, UUID.randomUUID().toString()).set(BOOKING.EXPIRES_AT, expiry.atOffset(ZoneOffset.UTC))
            .set(BOOKING.PAYMENT_REQUESTED_AT, if (payment) now.minusSeconds(60).atOffset(ZoneOffset.UTC) else null)
            .set(BOOKING.CREATED_AT, now.atOffset(ZoneOffset.UTC)).set(BOOKING.VERSION, 0L)
            .returning(BOOKING.ID).fetchOne()
        val id = requireNotNull(record?.id)
        dsl.insertInto(BOOKING_SEAT).set(BOOKING_SEAT.BOOKING_ID, id).set(BOOKING_SEAT.SEAT_ID, seat)
            .set(BOOKING_SEAT.PRICE, 100L).execute()
        if (state == "CONFIRMED") {
            dsl.insertInto(SOLD_SEAT).set(SOLD_SEAT.SCHEDULE_ID, scheduleId).set(SOLD_SEAT.SEAT_ID, seat)
                .set(SOLD_SEAT.BOOKING_ID, id).set(SOLD_SEAT.SOLD_AT, now.atOffset(ZoneOffset.UTC)).execute()
        }
        return id
    }

    @Test
    fun `잔여 좌석은 판매와 유효한 예매를 제외하고 만료와 결제 유예를 반영한다`() {
        val scheduleId = schedule(System.nanoTime(), 3)
        booking(1, scheduleId, 1, "CONFIRMED", now.plusSeconds(60))
        booking(1, scheduleId, 2, "PENDING", now.plusSeconds(60))
        booking(1, scheduleId, 3, "PENDING", now.minusSeconds(1))
        booking(1, scheduleId, 4, "PENDING", now.minusSeconds(60), payment = true)
        booking(1, scheduleId, 5, "PENDING", now.minusSeconds(1800), payment = true)

        mvc.perform(get("/api/v1/schedules/availability").param("scheduleIds", "$scheduleId,${scheduleId + 1}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].totalSeats").value(6)).andExpect(jsonPath("$[0].availableSeats").value(3))
        mvc.perform(get("/api/v1/schedules/availability").param("scheduleIds", (1..51).joinToString(",")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `내 예매에 공연 ID가 있으며 공연별로 필터한다`() {
        val (memberId, token) = member()
        val firstPerformance = System.nanoTime()
        val first = schedule(firstPerformance, 1)
        val second = schedule(firstPerformance + 1, 1)
        val id = booking(memberId, first, 1, "PENDING", now.plusSeconds(60))
        booking(memberId, second, 1, "PENDING", now.plusSeconds(60))

        mvc.perform(get("/api/v1/bookings/me").param("performanceId", "$firstPerformance").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].bookingId").value(id)).andExpect(jsonPath("$.items[0].performanceId").value(firstPerformance))
    }

    @Test
    fun `판매 현황은 본인 회차만 보이고 주최자만 접근한다`() {
        val (organizerId, organizer) = member("ORGANIZER")
        val (otherId, _) = member("ORGANIZER")
        val (_, user) = member()
        val performanceId = System.nanoTime()
        val mine = schedule(performanceId, organizerId)
        val others = schedule(performanceId + 1, otherId)
        booking(1, mine, 1, "CONFIRMED", now.plusSeconds(60))
        booking(1, mine, 2, "PENDING", now.plusSeconds(60))

        // 다른 테스트가 고정 organizerId로 등록한 회차와 생성된 회원 id가 겹칠 수 있어 건수 대신 회차 id로 검증한다
        val mineRow = "$[?(@.scheduleId == $mine)]"
        mvc.perform(get("/api/v1/organizer/sales").header("Authorization", "Bearer $organizer"))
            .andExpect(status().isOk).andExpect(jsonPath(mineRow, hasSize<Any>(1)))
            .andExpect(jsonPath("$[?(@.scheduleId == $others)]").isEmpty)
            .andExpect(jsonPath("$mineRow.totalSeats").value(6))
            .andExpect(jsonPath("$mineRow.soldSeats").value(1)).andExpect(jsonPath("$mineRow.confirmedAmount").value(100))
        mvc.perform(get("/api/v1/organizer/sales").header("Authorization", "Bearer $user"))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
        mvc.perform(get("/api/v1/organizer/sales"))
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
    }
}
