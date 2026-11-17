package io.github.ticketing.coupon

/**
 * 쿠폰 예약을 거부했다(coupon 발행).
 *
 * reason 값: `NOT_FOUND`, `NOT_OWNER`, `NOT_AVAILABLE`(ISSUED가 아니거나 다른 주문이 선점),
 * `OUT_OF_VALID_PERIOD`, `PERFORMANCE_MISMATCH`, `BELOW_MIN_ORDER_AMOUNT`, `AMOUNT_BELOW_MINIMUM`(할인 후 결제액 1원 미만).
 */
data class CouponRejected(val orderId: Long, val issuedCouponId: Long, val reason: String)
