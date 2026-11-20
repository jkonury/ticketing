package io.github.ticketing.booking.domain

import java.math.BigDecimal
import java.time.Instant

/** 쿠폰을 적용(교체 포함)한다. 결과는 coupon 모듈의 응답으로 [onCouponReserved]·[onCouponRejected]가 확정한다. */
fun Booking.applyCoupon(issuedCouponId: Long, now: Instant): CouponChange {
    requireCouponChangeable(now)
    val current = appliedCoupon
    if (current != null && current.issuedCouponId == issuedCouponId && current.state == CouponState.RESERVED) {
        return CouponChange.NONE
    }
    couponAttemptId += 1
    appliedCoupon = AppliedCoupon(issuedCouponId, CouponState.RESERVING, null, null, couponAttemptId)
    return CouponChange(releasedCoupon = current?.reservedCoupon(), reservationRequested = true)
}

fun Booking.removeCoupon(now: Instant): CouponChange {
    requireCouponChangeable(now)
    val current = appliedCoupon ?: return CouponChange.NONE
    appliedCoupon = null
    return CouponChange(releasedCoupon = current.reservedCoupon(), reservationRequested = false)
}

/**
 * coupon의 예약 성공을 반영한다. 현재 RESERVING 중인 쿠폰의 응답이 아니면(교체·해제·종료 이후의 늦은 응답) 반영하지 않고 false다.
 * 호출자는 false일 때 그 쿠폰의 예약을 해제해야 한다.
 */
fun Booking.onCouponReserved(issuedCouponId: Long, discount: BigDecimal, reservationAttemptId: Long): Boolean {
    val current = awaitingCoupon(issuedCouponId, reservationAttemptId) ?: return false
    if (discount.signum() <= 0 || discount >= listAmount) throw InvalidCouponDiscountException()
    appliedCoupon = current.copy(state = CouponState.RESERVED, discountAmount = discount)
    return true
}

/** 반영하면 true, 늦은 응답이라 무시하면 false다. 거절되면 쿠폰 없이 정가로 진행한다. */
fun Booking.onCouponRejected(issuedCouponId: Long, reason: String, reservationAttemptId: Long): Boolean {
    val current = awaitingCoupon(issuedCouponId, reservationAttemptId) ?: return false
    appliedCoupon = current.copy(state = CouponState.REJECTED, rejectReason = reason)
    return true
}

internal fun Booking.requireNoPendingCoupon() {
    if (appliedCoupon?.state == CouponState.RESERVING) throw CouponPendingException()
}

private fun Booking.requireCouponChangeable(now: Instant) {
    val unchangeable = status != BookingStatus.PENDING || paymentRequestedAt != null
    if (unchangeable) throw CouponChangeNotAllowedException()
    if (isExpired(now)) throw BookingExpiredException()
    requireNoPendingCoupon()
}

private fun AppliedCoupon.reservedCoupon(): AppliedCoupon? = takeIf { state == CouponState.RESERVED }

fun AppliedCoupon.matches(issuedCouponId: Long, reservationAttemptId: Long): Boolean =
    this.issuedCouponId == issuedCouponId && this.reservationAttemptId == reservationAttemptId

private fun Booking.awaitingCoupon(issuedCouponId: Long, reservationAttemptId: Long): AppliedCoupon? = appliedCoupon?.takeIf {
    status == BookingStatus.PENDING && it.state == CouponState.RESERVING && it.matches(issuedCouponId, reservationAttemptId)
}

/** 예매가 PENDING에서 끝날 때 coupon에 해제를 요청해야 하는 쿠폰이다. RESERVING도 포함한다(늦게 온 Reserved는 결과 리스너가 해제한다). */
fun Booking.couponToRelease(): AppliedCoupon? =
    appliedCoupon?.takeIf { it.state == CouponState.RESERVED || it.state == CouponState.RESERVING }
