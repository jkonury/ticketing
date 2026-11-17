package io.github.ticketing.coupon

import java.time.Instant

/** 결제 확정으로 예매가 쿠폰 사용 확정을 요청했다(booking 발행). 결제가 끝났으므로 coupon은 거부하지 않고 USED로 맞춘다. */
data class CouponUseConfirmed(val orderId: Long, val issuedCouponId: Long, val confirmedAt: Instant)
