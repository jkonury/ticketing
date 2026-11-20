package io.github.ticketing.booking.application.port.provided

import java.math.BigDecimal

/** coupon의 예약 결과를 예매에 반영한다. 같은 결과가 다시 와도 결과가 같다. */
interface HandleCouponResultUseCase {
    fun onReserved(bookingId: Long, issuedCouponId: Long, discountAmount: BigDecimal, reservationAttemptId: Long)

    fun onRejected(bookingId: Long, issuedCouponId: Long, reason: String, reservationAttemptId: Long)
}
