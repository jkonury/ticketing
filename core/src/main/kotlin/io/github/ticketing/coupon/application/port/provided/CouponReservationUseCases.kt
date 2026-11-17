package io.github.ticketing.coupon.application.port.provided

import io.github.ticketing.coupon.CouponReleaseRequested
import io.github.ticketing.coupon.CouponReservationRequested
import io.github.ticketing.coupon.CouponUseConfirmed
import java.time.Instant

interface ReserveCouponUseCase {
    /** 예약 결과는 CouponReserved 또는 CouponRejected 이벤트로 발행한다. */
    fun reserve(request: CouponReservationRequested)
}

interface ReleaseCouponUseCase {
    fun release(request: CouponReleaseRequested)
}

interface ReleaseStaleCouponReservationUseCase {
    /** 기한이 cutoff보다 앞선 RESERVED 쿠폰을 ISSUED로 되돌린다. 되돌렸으면 true. */
    fun releaseIfStale(issuedCouponId: Long, cutoff: Instant): Boolean
}

interface ConfirmCouponUseUseCase {
    fun confirm(request: CouponUseConfirmed)
}
