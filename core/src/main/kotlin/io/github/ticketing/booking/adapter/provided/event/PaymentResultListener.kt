package io.github.ticketing.booking.adapter.provided.event

import io.github.ticketing.booking.application.port.provided.HandlePaymentResultUseCase
import io.github.ticketing.payment.PaymentFailed
import io.github.ticketing.payment.PaymentRefunded
import io.github.ticketing.payment.PaymentSucceeded
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * 결제 결과를 유스케이스에 넘기기만 한다. 같은 이벤트가 다시 와도 유스케이스가 멱등이다.
 * 낙관적 락 충돌은 삼키지 않고 던져 이벤트 발행 레지스트리가 다시 시도하게 한다.
 */
@Component
class PaymentResultListener(private val handlePaymentResult: HandlePaymentResultUseCase) {
    @ApplicationModuleListener
    fun on(event: PaymentSucceeded) {
        handlePaymentResult.onPaymentSucceeded(event.paymentId, event.orderId, event.amount)
    }

    @ApplicationModuleListener
    fun on(event: PaymentFailed) {
        handlePaymentResult.onPaymentFailed(event.paymentId, event.orderId, event.reason)
    }

    @ApplicationModuleListener
    fun on(event: PaymentRefunded) {
        handlePaymentResult.onPaymentRefunded(event.paymentId, event.orderId)
    }
}
