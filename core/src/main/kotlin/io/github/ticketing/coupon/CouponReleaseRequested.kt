package io.github.ticketing.coupon

/** 예매가 쿠폰 예약 해제를 요청했다(booking 발행). 주문과 시도가 일치하는 예약을 ISSUED로 복원한다. */
data class CouponReleaseRequested(val orderId: Long, val issuedCouponId: Long, val reason: String, val reservationAttemptId: Long = 0)
