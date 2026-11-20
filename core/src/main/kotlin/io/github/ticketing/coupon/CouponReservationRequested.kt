package io.github.ticketing.coupon

import java.math.BigDecimal
import java.time.Instant

/** 예매가 주문에 쿠폰 적용을 요청했다(booking 발행). 멱등 키는 (orderId, issuedCouponId, reservationAttemptId)다. */
data class CouponReservationRequested(
    val orderId: Long,
    val memberId: Long,
    val issuedCouponId: Long,
    val performanceId: Long,
    val listAmount: BigDecimal,
    val requestedAt: Instant,
    val reservationAttemptId: Long = 0,
)
