package io.github.ticketing.payment.application.service

import io.github.ticketing.payment.PaymentFailed
import io.github.ticketing.payment.application.port.provided.RequestPaymentCommand
import io.github.ticketing.payment.application.port.provided.RequestRefundCommand
import io.github.ticketing.payment.application.port.required.PaymentCommandPort
import io.github.ticketing.payment.domain.*
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class PaymentTransactionService(
    private val payments: PaymentCommandPort,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {

    @Transactional
    fun preparePayment(command: RequestPaymentCommand): Payment? {
        val orderId = OrderId(command.orderId)
        val existing = payments.findByOrderId(orderId)
        val keyOwner = payments.findByIdempotencyKey(command.idempotencyKey)
        if (keyOwner != null && keyOwner.orderId != orderId) throw PaymentContractMismatchException()

        val payment = existing ?: payments.save(Payment.request(orderId, MemberId(command.memberId), command.amount, command.idempotencyKey, command.requestedAt))

        val sameContract = payment.memberId.value == command.memberId &&
            payment.amount.compareTo(command.amount) == 0 &&
            payment.idempotencyKey == command.idempotencyKey
        if (!sameContract) throw PaymentContractMismatchException()

        return payment.takeIf { it.status == PaymentStatus.REQUESTED && it.pgTransactionId == null }
    }

    @Transactional
    fun acceptPayment(orderId: Long, transactionId: String) {
        val payment = find(orderId)
        payment.attachPgTransaction(transactionId)
        payments.save(payment)
    }

    @Transactional
    fun rejectPayment(orderId: Long, reason: String) {
        val payment = find(orderId)
        if (payment.status != PaymentStatus.REQUESTED) return

        val now = clock.instant()
        payment.fail(reason, now)
        val saved = payments.save(payment)
        events.publishEvent(PaymentFailed(requireNotNull(saved.id).value, saved.orderId.value, reason, now))
    }

    @Transactional
    fun prepareRefund(command: RequestRefundCommand): Payment? {
        val payment = find(command.orderId)
        if (payment.status == PaymentStatus.REFUNDED) return null
        if (payment.requestRefund(command.requestedAt) == TransitionResult.Applied) return payments.save(payment)
        return payment
    }

    private fun find(orderId: Long): Payment = payments.findByOrderId(OrderId(orderId)) ?: throw PaymentRuleException("PAYMENT_NOT_FOUND")
}
