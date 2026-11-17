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
    appliedCoupon = AppliedCoupon(issuedCouponId, CouponState.RESERVING, null, null)
    return CouponChange(releasedIssuedCouponId = current?.reservedIssuedCouponId(), reservationRequested = true)
}

fun Booking.removeCoupon(now: Instant): CouponChange {
    requireCouponChangeable(now)
    val current = appliedCoupon ?: return CouponChange.NONE
    appliedCoupon = null
    return CouponChange(releasedIssuedCouponId = current.reservedIssuedCouponId(), reservationRequested = false)
}

/**
 * coupon의 예약 성공을 반영한다. 현재 RESERVING 중인 쿠폰의 응답이 아니면(교체·해제·종료 이후의 늦은 응답) 반영하지 않고 false다.
 * 호출자는 false일 때 그 쿠폰의 예약을 해제해야 한다.
 */
fun Booking.onCouponReserved(issuedCouponId: Long, discount: BigDecimal): Boolean {
    if (!isAwaitingCoupon(issuedCouponId)) return false
    if (discount.signum() <= 0 || discount >= listAmount) throw InvalidCouponDiscountException()
    appliedCoupon = AppliedCoupon(issuedCouponId, CouponState.RESERVED, discount, null)
    return true
}

/** 반영하면 true, 늦은 응답이라 무시하면 false다. 거절되면 쿠폰 없이 정가로 진행한다. */
fun Booking.onCouponRejected(issuedCouponId: Long, reason: String): Boolean {
    if (!isAwaitingCoupon(issuedCouponId)) return false
    appliedCoupon = AppliedCoupon(issuedCouponId, CouponState.REJECTED, null, reason)
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

private fun AppliedCoupon.reservedIssuedCouponId(): Long? = issuedCouponId.takeIf { state == CouponState.RESERVED }

private fun Booking.isAwaitingCoupon(issuedCouponId: Long): Boolean {
    val current = appliedCoupon ?: return false
    return status == BookingStatus.PENDING && current.issuedCouponId == issuedCouponId && current.state == CouponState.RESERVING
}

/** 예매가 PENDING에서 끝날 때 coupon에 해제를 요청해야 하는 쿠폰이다. RESERVING도 포함한다(늦게 온 Reserved는 결과 리스너가 해제한다). */
fun Booking.couponToRelease(): Long? =
    appliedCoupon?.takeIf { it.state == CouponState.RESERVED || it.state == CouponState.RESERVING }?.issuedCouponId
