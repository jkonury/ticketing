package io.github.ticketing.booking.application.port.provided

import java.math.BigDecimal

/** 결제 결과를 예매에 반영한다. 같은 결과가 다시 와도 결과가 같다. */
interface HandlePaymentResultUseCase {
    fun onPaymentSucceeded(paymentId: Long, bookingId: Long, amount: BigDecimal)

    fun onPaymentFailed(paymentId: Long, bookingId: Long, reason: String)

    fun onPaymentRefunded(paymentId: Long, bookingId: Long)
}
