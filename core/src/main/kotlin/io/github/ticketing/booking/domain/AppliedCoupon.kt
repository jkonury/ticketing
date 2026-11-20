package io.github.ticketing.booking.domain

import java.math.BigDecimal

enum class CouponState { RESERVING, RESERVED, REJECTED }

/** 예매에 적용한 쿠폰. [discountAmount]는 RESERVED일 때만, [rejectReason]은 REJECTED일 때만 있다. */
data class AppliedCoupon(
    val issuedCouponId: Long,
    val state: CouponState,
    val discountAmount: BigDecimal?,
    val rejectReason: String?,
    val reservationAttemptId: Long,
)

/** 쿠폰 변경이 요구하는 후속 조치. 서비스가 이벤트로 옮긴다. */
data class CouponChange(val releasedCoupon: AppliedCoupon?, val reservationRequested: Boolean) {
    companion object {
        val NONE = CouponChange(null, false)
    }
}
