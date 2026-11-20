package io.github.ticketing.coupon

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.MutableClock
import io.github.ticketing.coupon.application.port.provided.ConfirmCouponUseUseCase
import io.github.ticketing.coupon.application.port.provided.ReleaseCouponUseCase
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
import io.github.ticketing.coupon.domain.OrderId
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RecordApplicationEvents
// 서비스 상태를 검증하는 동안 커밋 후 실행되는 예매 보상 리스너가 가짜 주문의 쿠폰을 해제하지 않게 한다.
@Transactional
class CouponReservationIntegrationTests(
    private val reserveUseCase: ReserveCouponUseCase,
    private val releaseUseCase: ReleaseCouponUseCase,
    private val confirmUseCase: ConfirmCouponUseUseCase,
    private val definitions: CouponDefinitionCommandPort,
    private val issuedCoupons: IssuedCouponCommandPort,
    private val dsl: DSLContext,
    private val clock: MutableClock,
    private val tx: TransactionTemplate,
    private val publisher: ApplicationEventPublisher,
) : IntegrationTestSupport() {

    private val now = Instant.parse("2026-11-11T00:00:00Z")
    private val memberId = 7001L
    private val performanceId = 10L

    @BeforeEach
    fun fixClock() {
        clock.now = now
    }

    private fun issueCoupon(
        owner: Long = memberId,
        validFrom: Instant = now.minus(1, ChronoUnit.DAYS),
        validUntil: Instant = now.plus(30, ChronoUnit.DAYS),
        minOrderAmount: Long = 10000,
        performance: Long? = null,
        discountValue: Long = 1000,
    ): Long {
        val policy = DiscountPolicy(
            DiscountType.FIXED,
            BigDecimal(discountValue),
            null,
            BigDecimal(minOrderAmount),
            performance?.let { io.github.ticketing.coupon.domain.PerformanceId(it) },
            validFrom,
            validUntil,
        )
        val definition = definitions.save(CouponDefinition.create("테스트 쿠폰", null, policy, IssueMode.DIRECT, null, now))
        val definitionId = requireNotNull(definition.id)
        val issued = issuedCoupons.issue(IssuedCoupon.issue(definitionId, MemberId(owner), now)) as IssueResult.Issued
        return requireNotNull(issued.coupon.id).value
    }

    private fun nextOrder() = orderSeq.incrementAndGet()

    private fun request(orderId: Long, couponId: Long, owner: Long = memberId, amount: Long = 50000) =
        CouponReservationRequested(orderId, owner, couponId, performanceId, BigDecimal(amount), now)

    private fun coupon(id: Long) = requireNotNull(issuedCoupons.findById(IssuedCouponId(id)))

    private fun rejectedReasons(events: ApplicationEvents) = events.stream(CouponRejected::class.java).map { it.reason }.toList()

    @Test
    fun `예약에 성공하면 RESERVED가 되고 할인액과 만료 시각이 기록되며 Reserved를 발행한다`(events: ApplicationEvents) {
        val couponId = issueCoupon()
        val orderId = nextOrder()

        reserveUseCase.reserve(request(orderId, couponId))

        val reserved = coupon(couponId)
        assertThat(reserved.status).isEqualTo(IssuedCouponStatus.RESERVED)
        assertThat(reserved.orderId).isEqualTo(OrderId(orderId))
        assertThat(reserved.discountAmount).isEqualByComparingTo("1000")
        assertThat(reserved.reservedUntil).isEqualTo(now.plus(1, ChronoUnit.HOURS))
        assertThat(events.stream(CouponReserved::class.java).toList())
            .singleElement()
            .satisfies({ assertThat(it.orderId).isEqualTo(orderId) })
    }

    @Test
    fun `같은 요청이 재전달되면 같은 할인액으로 Reserved를 다시 발행한다`(events: ApplicationEvents) {
        val couponId = issueCoupon()
        val orderId = nextOrder()

        reserveUseCase.reserve(request(orderId, couponId))
        reserveUseCase.reserve(request(orderId, couponId))

        val reserved = events.stream(CouponReserved::class.java).toList()
        assertThat(reserved).hasSize(2)
        assertThat(reserved).allSatisfy { assertThat(it.discountAmount).isEqualByComparingTo("1000") }
        assertThat(coupon(couponId).status).isEqualTo(IssuedCouponStatus.RESERVED)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `같은 주문의 재적용은 이전 해제 처리 순서와 관계없이 새 시도를 예약한다`(releaseFirst: Boolean, events: ApplicationEvents) {
        val couponId = issueCoupon()
        val orderId = nextOrder()
        val first = request(orderId, couponId).copy(reservationAttemptId = 1)
        val second = first.copy(reservationAttemptId = 2)
        val release = CouponReleaseRequested(orderId, couponId, "REMOVED", first.reservationAttemptId)
        reserveUseCase.reserve(first)

        if (releaseFirst) releaseUseCase.release(release)
        reserveUseCase.reserve(second)
        releaseUseCase.release(release)
        confirmUseCase.confirm(CouponUseConfirmed(orderId, couponId, now, first.reservationAttemptId))
        reserveUseCase.reserve(first)
        reserveUseCase.reserve(second)

        assertThat(coupon(couponId).status).isEqualTo(IssuedCouponStatus.RESERVED)
        assertThat(coupon(couponId).reservationAttemptId).isEqualTo(second.reservationAttemptId)
        assertThat(coupon(couponId).discountAmount).isEqualByComparingTo("1000")
        assertThat(events.stream(CouponReserved::class.java).map { it.reservationAttemptId }.toList()).containsExactly(1L, 2L, 2L)
        assertThat(rejectedReasons(events)).containsExactly("NOT_AVAILABLE")
    }

    @Test
    fun `해제가 요청보다 먼저 오면 해당 시도는 재전달되어도 예약되지 않는다`(events: ApplicationEvents) {
        val couponId = issueCoupon()
        val orderId = nextOrder()
        val request = request(orderId, couponId).copy(reservationAttemptId = 1)
        val release = CouponReleaseRequested(orderId, couponId, "EXPIRED", request.reservationAttemptId)

        releaseUseCase.release(release)
        reserveUseCase.reserve(request)
        reserveUseCase.reserve(request)
        confirmUseCase.confirm(CouponUseConfirmed(orderId, couponId, now, request.reservationAttemptId))

        assertThat(rejectedReasons(events)).containsExactly("NOT_AVAILABLE", "NOT_AVAILABLE")
        assertThat(coupon(couponId).status).isEqualTo(IssuedCouponStatus.ISSUED)
    }

    @Test
    fun `선점이 풀린 뒤에도 앞서 해제 또는 거절된 주문의 시도는 다시 예약하지 못한다`(events: ApplicationEvents) {
        val couponId = issueCoupon()
        val first = request(nextOrder(), couponId).copy(reservationAttemptId = 1)
        val second = request(nextOrder(), couponId).copy(reservationAttemptId = 1)
        reserveUseCase.reserve(first)
        reserveUseCase.reserve(second)

        releaseUseCase.release(CouponReleaseRequested(first.orderId, couponId, "REMOVED", first.reservationAttemptId))
        reserveUseCase.reserve(second)
        reserveUseCase.reserve(first)

        assertThat(rejectedReasons(events)).containsExactly("NOT_AVAILABLE", "NOT_AVAILABLE", "NOT_AVAILABLE")
        assertThat(coupon(couponId).status).isEqualTo(IssuedCouponStatus.ISSUED)
    }

    @Test
    fun `없는 쿠폰과 남의 쿠폰은 NOT_FOUND와 NOT_OWNER로 거부한다`(events: ApplicationEvents) {
        val couponId = issueCoupon()

        reserveUseCase.reserve(request(nextOrder(), Long.MAX_VALUE))
        reserveUseCase.reserve(request(nextOrder(), couponId, owner = memberId + 1))

        assertThat(rejectedReasons(events)).containsExactly("NOT_FOUND", "NOT_OWNER")
        assertThat(coupon(couponId).status).isEqualTo(IssuedCouponStatus.ISSUED)
    }

    @Test
    fun `이미 다른 주문이 예약한 쿠폰은 NOT_AVAILABLE로 거부하고 선점은 유지된다`(events: ApplicationEvents) {
        val couponId = issueCoupon()
        val firstOrder = nextOrder()
        reserveUseCase.reserve(request(firstOrder, couponId))

        reserveUseCase.reserve(request(nextOrder(), couponId))

        assertThat(rejectedReasons(events)).containsExactly("NOT_AVAILABLE")
        assertThat(coupon(couponId).orderId).isEqualTo(OrderId(firstOrder))
    }

    @Test
    fun `할인 조건에 맞지 않으면 사유별로 거부하고 쿠폰은 ISSUED로 남는다`(events: ApplicationEvents) {
        val expired = issueCoupon(validFrom = now.minus(10, ChronoUnit.DAYS), validUntil = now.minus(1, ChronoUnit.DAYS))
        val otherPerformance = issueCoupon(performance = performanceId + 1)
        val belowMin = issueCoupon(minOrderAmount = 100000)
        val belowOne = issueCoupon(discountValue = 50000, minOrderAmount = 0)

        reserveUseCase.reserve(request(nextOrder(), expired))
        reserveUseCase.reserve(request(nextOrder(), otherPerformance))
        reserveUseCase.reserve(request(nextOrder(), belowMin))
        reserveUseCase.reserve(request(nextOrder(), belowOne))

        assertThat(rejectedReasons(events))
            .containsExactly("OUT_OF_VALID_PERIOD", "PERFORMANCE_MISMATCH", "BELOW_MIN_ORDER_AMOUNT", "AMOUNT_BELOW_MINIMUM")
        assertThat(listOf(expired, otherPerformance, belowMin, belowOne).map { coupon(it).status }).containsOnly(IssuedCouponStatus.ISSUED)
    }

    @Test
    fun `해제하면 ISSUED로 복원되고 재전달과 다른 주문의 해제는 무시한다`() {
        val couponId = issueCoupon()
        val orderId = nextOrder()
        reserveUseCase.reserve(request(orderId, couponId))

        releaseUseCase.release(CouponReleaseRequested(orderId + 1000, couponId, "OTHER"))
        assertThat(coupon(couponId).status).isEqualTo(IssuedCouponStatus.RESERVED)

        releaseUseCase.release(CouponReleaseRequested(orderId, couponId, "PAYMENT_FAILED"))
        releaseUseCase.release(CouponReleaseRequested(orderId, couponId, "PAYMENT_FAILED"))

        val released = coupon(couponId)
        assertThat(released.status).isEqualTo(IssuedCouponStatus.ISSUED)
        assertThat(released.orderId).isNull()
        assertThat(released.discountAmount).isNull()
    }

    @Test
    fun `사용 확정은 USED로 바꾸고 재전달은 무시하며 불일치를 기록하지 않는다`() {
        val couponId = issueCoupon()
        val orderId = nextOrder()
        reserveUseCase.reserve(request(orderId, couponId))

        confirmUseCase.confirm(CouponUseConfirmed(orderId, couponId, now))
        confirmUseCase.confirm(CouponUseConfirmed(orderId, couponId, now.plusSeconds(5)))

        val used = coupon(couponId)
        assertThat(used.status).isEqualTo(IssuedCouponStatus.USED)
        assertThat(used.usedAt).isEqualTo(now)
        assertThat(mismatchCount(couponId)).isZero()
    }

    @Test
    fun `해제된 쿠폰에 늦은 사용 확정이 오면 강제로 USED로 바꾸고 불일치를 한 번 기록한다`() {
        val couponId = issueCoupon()
        val orderId = nextOrder()
        reserveUseCase.reserve(request(orderId, couponId))
        releaseUseCase.release(CouponReleaseRequested(orderId, couponId, "EXPIRED"))

        confirmUseCase.confirm(CouponUseConfirmed(orderId, couponId, now))
        confirmUseCase.confirm(CouponUseConfirmed(orderId, couponId, now))

        val used = coupon(couponId)
        assertThat(used.status).isEqualTo(IssuedCouponStatus.USED)
        assertThat(used.orderId).isEqualTo(OrderId(orderId))
        assertThat(mismatchCount(couponId)).isEqualTo(1)
        val row = dsl.fetchOne("SELECT previous_status, order_id FROM coupon.use_mismatch WHERE issued_coupon_id = ?", couponId)
        assertThat(row?.get("previous_status")).isEqualTo("ISSUED")
        assertThat(row?.get("order_id")).isEqualTo(orderId)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `리스너는 커밋 후 쿠폰을 예약하고 예매가 없으면 보상으로 해제한다`(events: ApplicationEvents) {
        val couponId = issueCoupon()
        val orderId = nextOrder()

        tx.executeWithoutResult { publisher.publishEvent(request(orderId, couponId)) }

        await().atMost(10, TimeUnit.SECONDS).untilAsserted {
            assertThat(events.stream(CouponReserved::class.java).filter { it.orderId == orderId }.count()).isPositive()
            assertThat(events.stream(CouponReleaseRequested::class.java).filter { it.orderId == orderId }.toList())
                .containsExactly(CouponReleaseRequested(orderId, couponId, "STALE_RESERVATION"))
            assertThat(coupon(couponId).status).isEqualTo(IssuedCouponStatus.ISSUED)
        }
    }

    private fun mismatchCount(couponId: Long): Int = dsl.fetchCount(
        dsl.selectFrom(org.jooq.impl.DSL.table("coupon.use_mismatch")).where(org.jooq.impl.DSL.field("issued_coupon_id").eq(couponId)),
    )

    private companion object {
        val orderSeq = AtomicLong(System.nanoTime())
    }
}
