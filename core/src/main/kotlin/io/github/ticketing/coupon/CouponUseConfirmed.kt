package io.github.ticketing.coupon

import java.time.Instant

/** 결제 확정으로 예매가 쿠폰 사용 확정을 요청했다(booking 발행). 일치하는 예약 시도의 쿠폰을 USED로 바꾼다. */
data class CouponUseConfirmed(val orderId: Long, val issuedCouponId: Long, val confirmedAt: Instant, val reservationAttemptId: Long = 0)
