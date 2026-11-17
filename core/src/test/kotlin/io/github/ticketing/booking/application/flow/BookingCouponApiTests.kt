package io.github.ticketing.booking.application.flow

import io.github.ticketing.EventCollector
import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.booking.application.port.provided.HandleCouponResultUseCase
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import io.github.ticketing.coupon.CouponReleaseRequested
import io.github.ticketing.coupon.CouponReservationRequested
import io.github.ticketing.payment.PaymentRequested
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * coupon의 결과가 비동기로 상태를 바꾸지 않도록 결과 유스케이스를 모의해 적용 후 상태를 RESERVING에 고정한다.
 * RESERVED는 DB를 직접 바꿔 만든다. 실제 Saga는 [BookingCouponSagaTests]가 검증한다.
 */
@MockitoBean(types = [HandleCouponResultUseCase::class])
class BookingCouponApiTests(
    private val mvc: MockMvc,
    private val register: RegisterScheduleSeatsUseCase,
    private val jdbc: JdbcTemplate,
    private val collector: EventCollector,
) : IntegrationTestSupport() {
    private val json = JsonMapper.builder().build()

    private fun newSchedule(): Long {
        val scheduleId = System.nanoTime()
        register.register(
            RegisterScheduleSeatsCommand(
                scheduleId,
                1,
                "쿠폰 적용 공연",
                2,
                Instant.parse("2027-01-01T10:00:00Z"),
                (1..2).map { RegisterScheduleSeatsCommand.SeatInfo(it.toLong(), "R", "A구역 ${it}번", BigDecimal(100000)) },
            ),
        )
        return scheduleId
    }

    private fun token(): String {
        val email = "u-${UUID.randomUUID()}@example.com"
        mvc.perform(
            post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123", "name" to "홍길동", "role" to "USER"))),
        ).andExpect(status().isCreated)
        val res = mvc.perform(
            post("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123"))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return json.readValue(res, Map::class.java)["accessToken"] as String
    }

    private fun book(token: String, scheduleId: Long): Long {
        val res = mvc.perform(
            post("/api/v1/bookings").header("Authorization", "Bearer $token").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("scheduleId" to scheduleId, "seatIds" to listOf(1, 2)))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return (json.readValue(res, Map::class.java)["bookingId"] as Number).toLong()
    }

    private fun applyCoupon(token: String, bookingId: Long, issuedCouponId: Long): ResultActions = mvc.perform(
        put("/api/v1/bookings/$bookingId/coupon").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf("issuedCouponId" to issuedCouponId))),
    )

    private fun removeCoupon(token: String, bookingId: Long): ResultActions =
        mvc.perform(delete("/api/v1/bookings/$bookingId/coupon").header("Authorization", "Bearer $token"))

    private fun pay(token: String, bookingId: Long): ResultActions = mvc.perform(
        post("/api/v1/bookings/$bookingId/payments").header("Authorization", "Bearer $token")
            .header("Idempotency-Key", UUID.randomUUID().toString()),
    )

    private fun markReserved(bookingId: Long, issuedCouponId: Long, discount: Long) {
        jdbc.update(
            "UPDATE booking.booking SET coupon_issued_id = ?, coupon_state = 'RESERVED', discount_amount = ? WHERE id = ?",
            issuedCouponId,
            discount,
            bookingId,
        )
    }

    private fun requests(bookingId: Long) = collector.of(CouponReservationRequested::class.java) { it.orderId == bookingId }

    private fun releases(bookingId: Long) = collector.of(CouponReleaseRequested::class.java) { it.orderId == bookingId }

    @Test
    fun `쿠폰을 적용하면 RESERVING 응답과 함께 CouponReservationRequested가 정가로 한 번 발행된다`() {
        val token = token()
        val id = book(token, newSchedule())

        applyCoupon(token, id, 11).andExpect(status().isOk)
            .andExpect(jsonPath("$.coupon.issuedCouponId").value(11))
            .andExpect(jsonPath("$.coupon.state").value("RESERVING"))
            .andExpect(jsonPath("$.listAmount").value(200000))
            .andExpect(jsonPath("$.totalAmount").value(200000))
            .andExpect(jsonPath("$.discountAmount").value(0))
            .andExpect(jsonPath("$.payableAmount").value(200000))

        assertThat(requests(id)).hasSize(1)
        assertThat(requests(id)[0].issuedCouponId).isEqualTo(11)
        assertThat(requests(id)[0].listAmount).isEqualByComparingTo(BigDecimal(200000))
        assertThat(releases(id)).isEmpty()
    }

    @Test
    fun `RESERVING 중 재적용과 해제와 결제 요청은 409 COUPON_PENDING이다`() {
        val token = token()
        val id = book(token, newSchedule())
        applyCoupon(token, id, 11).andExpect(status().isOk)

        applyCoupon(token, id, 12).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("COUPON_PENDING"))
        removeCoupon(token, id).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("COUPON_PENDING"))
        pay(token, id).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("COUPON_PENDING"))

        assertThat(collector.of(PaymentRequested::class.java) { it.orderId == id }).isEmpty()
    }

    @Test
    fun `RESERVED 쿠폰을 교체하면 Release가 Request보다 먼저 발행된다`() {
        val token = token()
        val id = book(token, newSchedule())
        applyCoupon(token, id, 11).andExpect(status().isOk)
        markReserved(id, 11, 30000)
        val before = collector.events.size

        applyCoupon(token, id, 12).andExpect(status().isOk)
            .andExpect(jsonPath("$.coupon.issuedCouponId").value(12))
            .andExpect(jsonPath("$.coupon.state").value("RESERVING"))
            .andExpect(jsonPath("$.discountAmount").value(0))

        val fresh = collector.events.drop(before).filter {
            (it is CouponReleaseRequested && it.orderId == id) || (it is CouponReservationRequested && it.orderId == id)
        }
        assertThat(fresh).hasSize(2)
        assertThat(fresh[0]).isInstanceOf(CouponReleaseRequested::class.java)
        assertThat((fresh[0] as CouponReleaseRequested).issuedCouponId).isEqualTo(11)
        assertThat((fresh[0] as CouponReleaseRequested).reason).isEqualTo("REPLACED")
        assertThat((fresh[1] as CouponReservationRequested).issuedCouponId).isEqualTo(12)
    }

    @Test
    fun `같은 쿠폰이 RESERVED이면 이벤트 없이 200이다`() {
        val token = token()
        val id = book(token, newSchedule())
        applyCoupon(token, id, 11).andExpect(status().isOk)
        markReserved(id, 11, 30000)

        applyCoupon(token, id, 11).andExpect(status().isOk)
            .andExpect(jsonPath("$.coupon.state").value("RESERVED"))
            .andExpect(jsonPath("$.discountAmount").value(30000))
            .andExpect(jsonPath("$.payableAmount").value(170000))

        assertThat(requests(id)).hasSize(1)
        assertThat(releases(id)).isEmpty()
    }

    @Test
    fun `RESERVED 쿠폰을 해제하면 REMOVED Release가 발행되고 coupon이 null이다`() {
        val token = token()
        val id = book(token, newSchedule())
        applyCoupon(token, id, 11).andExpect(status().isOk)
        markReserved(id, 11, 30000)

        removeCoupon(token, id).andExpect(status().isOk)
            .andExpect(jsonPath("$.coupon").doesNotExist())
            .andExpect(jsonPath("$.payableAmount").value(200000))

        assertThat(releases(id).map { it.reason to it.issuedCouponId }).containsExactly("REMOVED" to 11L)
    }

    @Test
    fun `쿠폰이 없어도 해제는 200이다`() {
        val token = token()
        val id = book(token, newSchedule())

        removeCoupon(token, id).andExpect(status().isOk).andExpect(jsonPath("$.coupon").doesNotExist())

        assertThat(releases(id)).isEmpty()
    }

    @Test
    fun `RESERVED 상태로 결제를 요청하면 PaymentRequested 금액은 payable이다`() {
        val token = token()
        val id = book(token, newSchedule())
        applyCoupon(token, id, 11).andExpect(status().isOk)
        markReserved(id, 11, 30000)

        pay(token, id).andExpect(status().isAccepted)

        assertThat(collector.of(PaymentRequested::class.java) { it.orderId == id }.single().amount).isEqualByComparingTo(BigDecimal(170000))
    }

    @Test
    fun `결제를 요청한 뒤 쿠폰을 바꾸면 409 COUPON_CHANGE_NOT_ALLOWED다`() {
        val token = token()
        val id = book(token, newSchedule())
        pay(token, id).andExpect(status().isAccepted)

        applyCoupon(token, id, 11).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("COUPON_CHANGE_NOT_ALLOWED"))
        removeCoupon(token, id).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("COUPON_CHANGE_NOT_ALLOWED"))
    }

    @Test
    fun `남의 예매는 403이고 쿠폰 없는 상세는 coupon이 null이다`() {
        val owner = token()
        val id = book(owner, newSchedule())

        applyCoupon(token(), id, 11).andExpect(status().isForbidden)

        mvc.perform(get("/api/v1/bookings/$id").header("Authorization", "Bearer $owner"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.coupon").doesNotExist())
            .andExpect(jsonPath("$.listAmount").value(200000))
            .andExpect(jsonPath("$.payableAmount").value(200000))
    }
}
