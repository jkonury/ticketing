package io.github.ticketing.coupon.application.port.required

import io.github.ticketing.coupon.domain.IssuedCouponId
import io.github.ticketing.coupon.domain.IssuedCouponStatus
import io.github.ticketing.coupon.domain.OrderId
import java.time.Instant

interface UseMismatchPort {
    fun record(issuedCouponId: IssuedCouponId, orderId: OrderId, previousStatus: IssuedCouponStatus, detectedAt: Instant, detail: String?)
}
