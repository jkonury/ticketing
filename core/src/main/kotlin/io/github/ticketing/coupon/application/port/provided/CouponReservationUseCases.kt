package io.github.ticketing.coupon.application.port.provided

import io.github.ticketing.coupon.CouponReleaseRequested
import io.github.ticketing.coupon.CouponReservationRequested
import io.github.ticketing.coupon.CouponUseConfirmed

interface ReserveCouponUseCase {
    /** 예약 결과는 CouponReserved 또는 CouponRejected 이벤트로 발행한다. */
    fun reserve(request: CouponReservationRequested)
}

interface ReleaseCouponUseCase {
    fun release(request: CouponReleaseRequested)
}

interface ConfirmCouponUseUseCase {
    fun confirm(request: CouponUseConfirmed)
}
