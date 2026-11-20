package io.github.ticketing.coupon

import java.math.BigDecimal

/** 쿠폰을 주문에 예약했다(coupon 발행). 같은 요청이 재전달되면 저장된 같은 할인액으로 다시 발행한다. */
data class CouponReserved(val orderId: Long, val issuedCouponId: Long, val discountAmount: BigDecimal, val reservationAttemptId: Long = 0)
