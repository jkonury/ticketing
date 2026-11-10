package io.github.ticketing.payment.application.service

import io.github.ticketing.payment.PaymentFailed
import io.github.ticketing.payment.PaymentRefunded
import io.github.ticketing.payment.PaymentSucceeded
import io.github.ticketing.payment.application.port.provided.HandlePgWebhookUseCase
import io.github.ticketing.payment.application.port.provided.PgWebhookCommand
import io.github.ticketing.payment.application.port.provided.PgWebhookOutcome
import io.github.ticketing.payment.application.port.provided.PgWebhookType
import io.github.ticketing.payment.application.port.required.PaymentCommandPort
import io.github.ticketing.payment.domain.OrderId
import io.github.ticketing.payment.domain.Payment
import io.github.ticketing.payment.domain.TransitionResult
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * 웹훅 기록과 상태 전이를 한 트랜잭션에서 한다. 처리하지 못하면 롤백되어 웹훅 기록도 남지 않으므로 PG 재시도가 다시 반영된다.
 */
@Service
class PgWebhookService(
    private val payments: PaymentCommandPort,
    private val events: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) : HandlePgWebhookUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(command: PgWebhookCommand): PgWebhookOutcome = try {
        translatingPaymentRules { requireNotNull(tx.execute { handleInTransaction(command) }) }
    } catch (e: PaymentRuleViolation) {
        log.error(
            "웹훅을 결제에 반영할 수 없다 eventId={} type={} transactionId={} orderId={} code={}",
            command.eventId, command.type, command.transactionId, command.orderId, e.code,
        )
        throw e
    }

    private fun handleInTransaction(command: PgWebhookCommand): PgWebhookOutcome {
        val now = clock.instant()
        if (!payments.recordWebhookEvent(command.eventId, command.type.name, command.transactionId, now)) {
            log.info("이미 받은 웹훅이라 무시한다 eventId={} transactionId={}", command.eventId, command.transactionId)
            return PgWebhookOutcome.DUPLICATE
        }
        val payment = findPayment(command)
        val paymentId = requireNotNull(payment.id).value
        val orderId = payment.orderId.value
        // 결제 요청 리스너가 거래 ID를 붙이기 전에 웹훅이 먼저 올 수 있다
        val attached = payment.pgTransactionId == null
        payment.attachPgTransaction(command.transactionId)

        val event: Any? = when (command.type) {
            PgWebhookType.PAYMENT_APPROVED -> applied(payment.approve(command.amount, now)) {
                PaymentSucceeded(paymentId, orderId, payment.amount, now)
            }
            PgWebhookType.PAYMENT_FAILED -> {
                val reason = command.failureReason ?: "PG_FAILED"
                applied(payment.fail(reason, now)) { PaymentFailed(paymentId, orderId, reason, now) }
            }
            PgWebhookType.REFUND_COMPLETED -> applied(payment.completeRefund(now)) { PaymentRefunded(paymentId, orderId, now) }
        }
        if (event != null || attached) payments.save(payment)
        // 이미 반영된 전이면 이벤트를 다시 발행하지 않는다
        event?.let(events::publishEvent)
        log.info("웹훅 반영 paymentId={} orderId={} type={} status={}", paymentId, orderId, command.type, payment.status)
        return PgWebhookOutcome.APPLIED
    }

    private fun findPayment(command: PgWebhookCommand): Payment =
        payments.findByPgTransactionId(command.transactionId)
            ?: command.orderId?.let { payments.findByOrderId(OrderId(it)) }
            ?: run {
                log.warn("웹훅의 결제를 찾을 수 없다 orderId={} transactionId={} type={}", command.orderId, command.transactionId, command.type)
                throw PgTransactionNotFoundException()
            }

    private inline fun applied(result: TransitionResult, event: () -> Any): Any? = when (result) {
        TransitionResult.Applied -> event()
        TransitionResult.AlreadyApplied -> null
    }
}
