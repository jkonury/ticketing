package io.github.ticketing.booking.application.port.provided

interface BookingCouponUseCase {
    /** 쿠폰 적용(교체 포함)을 요청한다. 결과는 coupon 응답으로 비동기 확정되며, 돌려주는 상세의 쿠폰 상태는 RESERVING이다. */
    fun apply(memberId: Long, bookingId: Long, issuedCouponId: Long): BookingDetailView

    /** 쿠폰을 해제한다. 쿠폰이 없어도 성공이다. */
    fun remove(memberId: Long, bookingId: Long): BookingDetailView
}
