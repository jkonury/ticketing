package io.github.ticketing.fakepg.payment.service

import io.github.ticketing.fakepg.payment.config.FakePgProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

@Service
class PaymentService(
    private val store: TransactionStore,
    private val scenarioResolver: ScenarioResolver,
    private val webhookSender: WebhookSender,
    private val properties: FakePgProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 새로 만들어졌을 때만 웹훅을 예약한다. 같은 키의 재요청은 거래도 웹훅도 만들지 않는다. */
    fun requestPayment(idempotencyKey: String, command: PaymentCommand): Registration {
        val registration = store.register(idempotencyKey, command) {
            Transaction(
                transactionId = "txn_" + UUID.randomUUID().toString().replace("-", ""),
                orderId = command.orderId,
                amount = command.amount,
                scenario = scenarioResolver.resolve(command.scenario),
                status = TransactionStatus.PENDING,
                requestedAt = clock.instant(),
            )
        }
        if (registration.created) {
            val tx = registration.transaction
            log.info("결제 요청 접수 transactionId={} scenario={}", tx.transactionId, tx.scenario)
            schedulePaymentResult(tx)
        }
        return registration
    }

    fun refund(transactionId: String, idempotencyKey: String): RefundResult {
        val result = store.refund(transactionId, idempotencyKey, clock.instant())
        if (result.created) {
            log.info("환불 접수 transactionId={}", transactionId)
            val event = webhookSender.newEvent(WebhookEventType.REFUND_COMPLETED, result.transaction)
            webhookSender.sendAfter(properties.webhook.delay, event)
        }
        return result
    }

    fun findTransactions(date: LocalDate): List<Transaction> = store.findByRequestedDate(date)

    // 거래 상태는 웹훅이 나가는 시점에 확정한다. 지연 시나리오에서는 그동안 PENDING으로 남는다.
    private fun schedulePaymentResult(tx: Transaction) {
        val webhook = properties.webhook
        when (tx.scenario) {
            Scenario.SUCCESS -> scheduleApproval(tx, webhook.delay, copies = 1)
            Scenario.FAIL -> scheduleFailure(tx, webhook.delay)
            // 클라이언트 응답만 늦고 결제 자체는 정상 승인된다
            Scenario.TIMEOUT -> scheduleApproval(tx, webhook.delay, copies = 1)
            Scenario.DELAYED -> scheduleApproval(tx, properties.behavior.delayedWebhookDelay, copies = 1)
            Scenario.DUPLICATE_WEBHOOK -> scheduleApproval(tx, webhook.delay, copies = 1 + webhook.duplicateCount)
        }
    }

    private fun scheduleApproval(tx: Transaction, delay: Duration, copies: Int) {
        webhookSender.scheduleAfter(delay) {
            val approved = store.approve(tx.transactionId, clock.instant()) ?: return@scheduleAfter
            val event = webhookSender.newEvent(WebhookEventType.PAYMENT_APPROVED, approved)
            webhookSender.sendAfter(Duration.ZERO, event, copies)
        }
    }

    private fun scheduleFailure(tx: Transaction, delay: Duration) {
        webhookSender.scheduleAfter(delay) {
            val failed = store.fail(tx.transactionId) ?: return@scheduleAfter
            val event = webhookSender.newEvent(WebhookEventType.PAYMENT_FAILED, failed, FAILURE_REASON)
            webhookSender.sendAfter(Duration.ZERO, event)
        }
    }

    companion object {
        const val FAILURE_REASON = "CARD_DECLINED"
    }
}
