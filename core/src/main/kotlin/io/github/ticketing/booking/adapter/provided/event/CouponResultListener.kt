package io.github.ticketing.booking.adapter.provided.event

import io.github.ticketing.booking.application.port.provided.HandleCouponResultUseCase
import io.github.ticketing.coupon.CouponRejected
import io.github.ticketing.coupon.CouponReserved
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/** coupon의 예약 결과를 유스케이스에 넘기기만 한다. orderId는 bookingId다. */
@Component
class CouponResultListener(private val handleCouponResult: HandleCouponResultUseCase) {
    @ApplicationModuleListener
    fun on(event: CouponReserved) {
        handleCouponResult.onReserved(event.orderId, event.issuedCouponId, event.discountAmount)
    }

    @ApplicationModuleListener
    fun on(event: CouponRejected) {
        handleCouponResult.onRejected(event.orderId, event.issuedCouponId, event.reason)
    }
}
