package io.github.ticketing.payment.adapter.provided.event

import io.github.ticketing.payment.RefundRequested
import io.github.ticketing.payment.application.port.provided.RequestRefundCommand
import io.github.ticketing.payment.application.port.provided.RequestRefundUseCase
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation

@Component
class RefundRequestListener(private val refunds: RequestRefundUseCase) {
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    fun on(event: RefundRequested) = refunds.request(RequestRefundCommand(event.orderId, event.reason, event.requestedAt))
}
