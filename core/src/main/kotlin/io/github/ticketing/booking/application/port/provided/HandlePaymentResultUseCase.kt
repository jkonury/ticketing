package io.github.ticketing.booking.application.port.provided

import java.math.BigDecimal
import java.time.Instant

/** 결제 결과를 예매에 반영한다. 같은 결과가 다시 와도 결과가 같다. */
interface HandlePaymentResultUseCase {
    /** [paidAt]은 PG가 알린 결제 발생 시각이다. 예매를 실제로 확정할 때 확정 지연 지표에 쓴다. */
    fun onPaymentSucceeded(paymentId: Long, bookingId: Long, amount: BigDecimal, paidAt: Instant)

    fun onPaymentFailed(paymentId: Long, bookingId: Long, reason: String)

    fun onPaymentRefunded(paymentId: Long, bookingId: Long)
}
