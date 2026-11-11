package io.github.ticketing.payment.application.service

import io.github.ticketing.payment.PaymentFailed
import io.github.ticketing.payment.PaymentRefunded
import io.github.ticketing.payment.PaymentSucceeded
import io.github.ticketing.payment.application.port.provided.*
import io.github.ticketing.payment.application.port.required.PaymentCommandPort
import io.github.ticketing.payment.domain.*
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class PaymentWebhookService(private val payments: PaymentCommandPort, private val events: ApplicationEventPublisher, private val clock: Clock) : HandlePaymentWebhookUseCase {
    @Transactional
    override fun handle(command: PaymentWebhookCommand) {
        require(command.eventId.isNotBlank() && command.transactionId.isNotBlank())
        if (!payments.recordWebhookEvent(command.eventId, command.type.name, command.transactionId, clock.instant())) return
        val payment = payments.findByPgTransactionId(command.transactionId) ?: payments.findByOrderId(OrderId(command.orderId))
            ?: throw PaymentRuleException("PAYMENT_NOT_FOUND")
        if (payment.orderId.value != command.orderId || payment.amount.compareTo(command.amount) != 0) throw PaymentContractMismatchException()
        payment.attachPgTransaction(command.transactionId)
        val result = when (command.type) {
            PaymentWebhookType.PAYMENT_APPROVED -> payment.approve(command.amount, command.occurredAt)
            PaymentWebhookType.PAYMENT_FAILED -> payment.fail(requireNotNull(command.failureReason).also { require(it.isNotBlank()) }, command.occurredAt)
            PaymentWebhookType.REFUND_COMPLETED -> payment.completeRefund(command.occurredAt)
        }
        val saved = payments.save(payment)
        if (result == TransitionResult.Applied) {
            val id = requireNotNull(saved.id).value
            val event = when (command.type) {
                PaymentWebhookType.PAYMENT_APPROVED -> PaymentSucceeded(id, command.orderId, saved.amount, command.occurredAt)
                PaymentWebhookType.PAYMENT_FAILED -> PaymentFailed(id, command.orderId, requireNotNull(command.failureReason), command.occurredAt)
                PaymentWebhookType.REFUND_COMPLETED -> PaymentRefunded(id, command.orderId, command.occurredAt)
            }
            events.publishEvent(event)
        }
    }
}
