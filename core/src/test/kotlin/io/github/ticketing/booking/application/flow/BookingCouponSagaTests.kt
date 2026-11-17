package io.github.ticketing.booking.application.flow

import io.github.ticketing.EventCollector
import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.MutableClock
import io.github.ticketing.booking.BookingConfirmed
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter.Companion.holdKey
import io.github.ticketing.booking.application.port.provided.ExpireBookingUseCase
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import io.github.ticketing.coupon.CouponReleaseRequested
import io.github.ticketing.coupon.CouponReservationRequested
import io.github.ticketing.coupon.CouponReserved
import io.github.ticketing.coupon.CouponUseConfirmed
import io.github.ticketing.coupon.application.port.provided.ReserveCouponUseCase
import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.application.port.required.IssueResult
import io.github.ticketing.coupon.application.port.required.IssuedCouponCommandPort
import io.github.ticketing.coupon.domain.CouponDefinition
import io.github.ticketing.coupon.domain.DiscountPolicy
import io.github.ticketing.coupon.domain.DiscountType
import io.github.ticketing.coupon.domain.IssueMode
import io.github.ticketing.coupon.domain.IssuedCoupon
import io.github.ticketing.coupon.domain.IssuedCouponId
import io.github.ticketing.coupon.domain.IssuedCouponStatus
import io.github.ticketing.coupon.domain.MemberId
import io.github.ticketing.payment.PaymentFailed
import io.github.ticketing.payment.PaymentRequested
import io.github.ticketing.payment.PaymentSucceeded
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** booking과 coupon의 실제 리스너를 모두 거치는 쿠폰 예약 Saga 통합 테스트. 쿠폰은 정액 30000원 할인이다. */
class BookingCouponSagaTests(
    private val mvc: MockMvc,
    private val register: RegisterScheduleSeatsUseCase,
    private val expire: ExpireBookingUseCase,
    private val reserveUseCase: ReserveCouponUseCase,
    private val definitions: CouponDefinitionCommandPort,
    private val issuedCoupons: IssuedCouponCommandPort,
    private val redis: StringRedisTemplate,
    private val jdbc: JdbcTemplate,
    private val publisher: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
    private val clock: MutableClock,
    private val collector: EventCollector,
) : IntegrationTestSupport() {
    private val json = JsonMapper.builder().build()

    private class Member(val id: Long, val token: String)

    private fun newSchedule(): Long {
        val scheduleId = System.nanoTime()
        register.register(
            RegisterScheduleSeatsCommand(
                scheduleId,
                1,
                "쿠폰 Saga 공연",
                2,
                Instant.parse("2027-01-01T10:00:00Z"),
                (1..4).map { RegisterScheduleSeatsCommand.SeatInfo(it.toLong(), "R", "A구역 ${it}번", BigDecimal(100000)) },
            ),
        )
        return scheduleId
    }

    private fun member(): Member {
        val email = "u-${UUID.randomUUID()}@example.com"
        mvc.perform(
            post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123", "name" to "홍길동", "role" to "USER"))),
        ).andExpect(status().isCreated)
        val res = mvc.perform(
            post("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123"))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val token = json.readValue(res, Map::class.java)["accessToken"] as String
        val id = requireNotNull(jdbc.queryForObject("SELECT id FROM member.member WHERE email = ?", Long::class.java, email))
        return Member(id, token)
    }

    private fun issueCoupon(owner: Long, minOrderAmount: Long = 10000): Long {
        val now = clock.instant()
        val policy = DiscountPolicy(
            DiscountType.FIXED,
            BigDecimal(30000),
            null,
            BigDecimal(minOrderAmount),
            null,
            now.minus(1, ChronoUnit.DAYS),
            now.plus(30, ChronoUnit.DAYS),
        )
        val definition = definitions.save(CouponDefinition.create("Saga 쿠폰", null, policy, IssueMode.DIRECT, null, now))
        val issued = issuedCoupons.issue(IssuedCoupon.issue(requireNotNull(definition.id), MemberId(owner), now)) as IssueResult.Issued
        return requireNotNull(issued.coupon.id).value
    }

    private fun couponStatus(id: Long) = requireNotNull(issuedCoupons.findById(IssuedCouponId(id))).status

    private fun awaitCoupon(id: Long, expected: IssuedCouponStatus) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted { assertThat(couponStatus(id)).isEqualTo(expected) }
    }

    private fun book(m: Member, scheduleId: Long, seatIds: List<Long> = listOf(1, 2)): Long {
        val res = mvc.perform(
            post("/api/v1/bookings").header("Authorization", "Bearer ${m.token}").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("scheduleId" to scheduleId, "seatIds" to seatIds))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return (json.readValue(res, Map::class.java)["bookingId"] as Number).toLong()
    }

    private fun applyCoupon(m: Member, bookingId: Long, issuedCouponId: Long): ResultActions = mvc.perform(
        put("/api/v1/bookings/$bookingId/coupon").header("Authorization", "Bearer ${m.token}")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf("issuedCouponId" to issuedCouponId))),
    )

    private fun pay(m: Member, bookingId: Long): ResultActions = mvc.perform(
        post("/api/v1/bookings/$bookingId/payments").header("Authorization", "Bearer ${m.token}")
            .header("Idempotency-Key", UUID.randomUUID().toString()),
    )

    private fun cancel(m: Member, bookingId: Long): ResultActions =
        mvc.perform(post("/api/v1/bookings/$bookingId/cancel").header("Authorization", "Bearer ${m.token}"))

    private fun detail(m: Member, bookingId: Long): Map<*, *> {
        val res = mvc.perform(get("/api/v1/bookings/$bookingId").header("Authorization", "Bearer ${m.token}"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return json.readValue(res, Map::class.java)
    }

    private fun couponState(m: Member, bookingId: Long): String? = (detail(m, bookingId)["coupon"] as Map<*, *>?)?.get("state") as String?

    private fun awaitStatus(m: Member, bookingId: Long, expected: String) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted { assertThat(detail(m, bookingId)["status"]).isEqualTo(expected) }
    }

    private fun awaitCouponState(m: Member, bookingId: Long, expected: String) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted { assertThat(couponState(m, bookingId)).isEqualTo(expected) }
    }

    private fun succeed(bookingId: Long, amount: BigDecimal) {
        tx.executeWithoutResult { publisher.publishEvent(PaymentSucceeded(bookingId, bookingId, amount, Instant.now())) }
    }

    private fun releases(bookingId: Long) = collector.of(CouponReleaseRequested::class.java) { it.orderId == bookingId }

    /** 쿠폰을 적용해 RESERVED가 된 예매를 만든다. */
    private fun reservedBooking(m: Member, scheduleId: Long, seatIds: List<Long> = listOf(1, 2)): Pair<Long, Long> {
        val couponId = issueCoupon(m.id)
        val id = book(m, scheduleId, seatIds)
        applyCoupon(m, id, couponId).andExpect(status().isOk)
        awaitCouponState(m, id, "RESERVED")
        awaitCoupon(couponId, IssuedCouponStatus.RESERVED)
        return id to couponId
    }

    @Test
    fun `적용하면 RESERVED가 되고 결제 성공으로 확정되면 쿠폰이 USED가 되며 BookingConfirmed에 금액 3종이 담긴다`() {
        val m = member()
        val (id, couponId) = reservedBooking(m, newSchedule())

        pay(m, id).andExpect(status().isAccepted)
        assertThat(collector.of(PaymentRequested::class.java) { it.orderId == id }.single().amount).isEqualByComparingTo(BigDecimal(170000))
        succeed(id, BigDecimal(170000))

        awaitStatus(m, id, "CONFIRMED")
        awaitCoupon(couponId, IssuedCouponStatus.USED)
        val confirmed = collector.of(BookingConfirmed::class.java) { it.bookingId == id }.single()
        assertThat(confirmed.amount).isEqualByComparingTo(BigDecimal(170000))
        assertThat(confirmed.listAmount).isEqualByComparingTo(BigDecimal(200000))
        assertThat(confirmed.discountAmount).isEqualByComparingTo(BigDecimal(30000))
        assertThat(collector.of(CouponUseConfirmed::class.java) { it.orderId == id }.single().issuedCouponId).isEqualTo(couponId)
        assertThat(releases(id)).isEmpty()
    }

    @Test
    fun `결제가 실패하면 쿠폰이 ISSUED로 복원된다`() {
        val m = member()
        val (id, couponId) = reservedBooking(m, newSchedule())
        pay(m, id).andExpect(status().isAccepted)

        tx.executeWithoutResult { publisher.publishEvent(PaymentFailed(1, id, "카드 한도 초과", Instant.now())) }

        awaitStatus(m, id, "CANCELLED")
        awaitCoupon(couponId, IssuedCouponStatus.ISSUED)
        assertThat(releases(id).map { it.reason }).containsExactly("PAYMENT_FAILED")
    }

    @Test
    fun `만료되면 쿠폰이 ISSUED로 복원된다`() {
        val m = member()
        val (id, couponId) = reservedBooking(m, newSchedule())
        clock.now = clock.now.plus(Duration.ofMinutes(6))

        expire.expireIfDue(id)

        awaitCoupon(couponId, IssuedCouponStatus.ISSUED)
        assertThat(releases(id).map { it.reason }).containsExactly("EXPIRED")
    }

    @Test
    fun `좌석이 이미 판매되어 취소되면 쿠폰이 ISSUED로 복원된다`() {
        val schedule = newSchedule()
        val a = member()
        val b = member()
        val (bookingA, couponId) = reservedBooking(a, schedule)
        redis.delete(listOf(holdKey(ScheduleId(schedule), SeatId(1)), holdKey(ScheduleId(schedule), SeatId(2))))
        val bookingB = book(b, schedule, listOf(2, 3))
        pay(a, bookingA).andExpect(status().isAccepted)
        pay(b, bookingB).andExpect(status().isAccepted)
        succeed(bookingB, BigDecimal(200000))
        awaitStatus(b, bookingB, "CONFIRMED")

        succeed(bookingA, BigDecimal(170000))

        awaitStatus(a, bookingA, "CANCELLED")
        awaitCoupon(couponId, IssuedCouponStatus.ISSUED)
        assertThat(releases(bookingA).map { it.reason }).containsExactly("SEAT_ALREADY_SOLD")
    }

    @Test
    fun `결제 전에 사용자가 취소하면 쿠폰이 ISSUED로 복원된다`() {
        val m = member()
        val (id, couponId) = reservedBooking(m, newSchedule())

        cancel(m, id).andExpect(status().isOk)

        awaitCoupon(couponId, IssuedCouponStatus.ISSUED)
        assertThat(releases(id).map { it.reason }).containsExactly("USER_CANCELLED")
    }

    @Test
    fun `확정 후 사용자가 취소해 환불해도 쿠폰은 USED로 남고 해제 요청이 없다`() {
        val m = member()
        val (id, couponId) = reservedBooking(m, newSchedule())
        pay(m, id).andExpect(status().isAccepted)
        succeed(id, BigDecimal(170000))
        awaitStatus(m, id, "CONFIRMED")
        awaitCoupon(couponId, IssuedCouponStatus.USED)

        cancel(m, id).andExpect(status().isOk)

        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(couponStatus(couponId)).isEqualTo(IssuedCouponStatus.USED)
            assertThat(releases(id)).isEmpty()
        }
    }

    @Test
    fun `만료된 뒤 늦게 도착한 Reserved는 즉시 해제되어 쿠폰이 ISSUED가 된다`() {
        val m = member()
        val couponId = issueCoupon(m.id)
        val id = book(m, newSchedule())
        clock.now = clock.now.plus(Duration.ofMinutes(6))
        expire.expireIfDue(id)

        reserveUseCase.reserve(CouponReservationRequested(id, m.id, couponId, 1, BigDecimal(200000), clock.instant()))

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(releases(id).map { it.reason }).contains("STALE_RESERVATION")
            assertThat(couponStatus(couponId)).isEqualTo(IssuedCouponStatus.ISSUED)
        }
    }

    @Test
    fun `쿠폰이 거부되면 PENDING을 유지하고 정가로 결제한다`() {
        val m = member()
        val couponId = issueCoupon(m.id, minOrderAmount = 1_000_000)
        val id = book(m, newSchedule())

        applyCoupon(m, id, couponId).andExpect(status().isOk)

        awaitCouponState(m, id, "REJECTED")
        assertThat(detail(m, id)["status"]).isEqualTo("PENDING")
        assertThat(couponStatus(couponId)).isEqualTo(IssuedCouponStatus.ISSUED)
        pay(m, id).andExpect(status().isAccepted)
        assertThat(collector.of(PaymentRequested::class.java) { it.orderId == id }.single().amount).isEqualByComparingTo(BigDecimal(200000))
        assertThat(releases(id)).isEmpty()
    }

    @Test
    fun `같은 Reserved가 재전달되어도 해제하지 않고 RESERVED를 유지한다`() {
        val m = member()
        val (id, couponId) = reservedBooking(m, newSchedule())

        tx.executeWithoutResult { publisher.publishEvent(CouponReserved(id, couponId, BigDecimal(30000))) }

        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(couponState(m, id)).isEqualTo("RESERVED")
            assertThat(couponStatus(couponId)).isEqualTo(IssuedCouponStatus.RESERVED)
            assertThat(releases(id)).isEmpty()
        }
    }
}
