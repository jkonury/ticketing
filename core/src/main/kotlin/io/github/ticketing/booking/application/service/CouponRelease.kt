package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.couponToRelease
import io.github.ticketing.coupon.CouponReleaseRequested
import org.springframework.context.ApplicationEventPublisher

/**
 * PENDING 예매가 만료·취소로 끝나는 모든 경로가 쓰는 쿠폰 복원 지점이다. 확정 후 환불은 쿠폰을 소멸시키므로 호출하지 않는다.
 */
internal fun ApplicationEventPublisher.requestCouponRelease(booking: Booking, reason: String) {
    val coupon = booking.couponToRelease() ?: return
    publishEvent(CouponReleaseRequested(requireNotNull(booking.id).value, coupon.issuedCouponId, reason, coupon.reservationAttemptId))
}
