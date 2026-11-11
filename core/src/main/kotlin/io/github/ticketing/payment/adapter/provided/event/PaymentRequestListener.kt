package io.github.ticketing.payment.adapter.provided.event

import io.github.ticketing.payment.PaymentRequested
import io.github.ticketing.payment.application.port.provided.RequestPaymentCommand
import io.github.ticketing.payment.application.port.provided.RequestPaymentUseCase
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation

@Component
class PaymentRequestListener(private val payments: RequestPaymentUseCase) {
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    fun on(event: PaymentRequested) =
        payments.request(RequestPaymentCommand(event.orderId, event.memberId, event.amount, event.idempotencyKey, event.requestedAt))
}
