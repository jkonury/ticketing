package io.github.ticketing.coupon.application.port.required

import io.github.ticketing.coupon.domain.IssuedCouponId
import io.github.ticketing.coupon.domain.OrderId

data class ReservationAttempt(val id: Long, val rejectReason: String? = null, val releasedVersion: Long? = null)

/** 발급 쿠폰 행을 잠근 트랜잭션에서 사용한다. */
interface ReservationAttemptPort {
    fun find(couponId: IssuedCouponId, orderId: OrderId): ReservationAttempt?

    fun exists(couponId: IssuedCouponId): Boolean

    fun save(couponId: IssuedCouponId, orderId: OrderId, attempt: ReservationAttempt)
}
