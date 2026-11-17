package io.github.ticketing.coupon

/** 예매가 쿠폰 예약 해제를 요청했다(booking 발행). 예약이 같은 주문일 때만 ISSUED로 복원한다. reason은 자유 문자열(로그용)이다. */
data class CouponReleaseRequested(val orderId: Long, val issuedCouponId: Long, val reason: String)
