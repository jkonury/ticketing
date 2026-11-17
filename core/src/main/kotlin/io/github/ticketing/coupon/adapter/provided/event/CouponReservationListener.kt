package io.github.ticketing.coupon.adapter.provided.event

import io.github.ticketing.coupon.CouponReleaseRequested
import io.github.ticketing.coupon.CouponReservationRequested
import io.github.ticketing.coupon.CouponUseConfirmed
import io.github.ticketing.coupon.application.port.provided.ConfirmCouponUseUseCase
import io.github.ticketing.coupon.application.port.provided.ReleaseCouponUseCase
import io.github.ticketing.coupon.application.port.provided.ReserveCouponUseCase
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
class CouponReservationListener(
    private val reserve: ReserveCouponUseCase,
    private val release: ReleaseCouponUseCase,
    private val confirm: ConfirmCouponUseUseCase,
) {
    @ApplicationModuleListener
    fun on(event: CouponReservationRequested) = reserve.reserve(event)

    @ApplicationModuleListener
    fun on(event: CouponReleaseRequested) = release.release(event)

    @ApplicationModuleListener
    fun on(event: CouponUseConfirmed) = confirm.confirm(event)
}
