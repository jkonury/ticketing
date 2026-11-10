package io.github.ticketing.payment.application.service

import io.github.ticketing.payment.PaymentFailed
import io.github.ticketing.payment.application.port.provided.ProcessPaymentCommand
import io.github.ticketing.payment.application.port.provided.ProcessPaymentUseCase
import io.github.ticketing.payment.application.port.required.PaymentCommandPort
import io.github.ticketing.payment.application.port.required.PgPaymentRequest
import io.github.ticketing.payment.application.port.required.PgPort
import io.github.ticketing.payment.application.port.required.PgRequestResult
import io.github.ticketing.payment.domain.MemberId
import io.github.ticketing.payment.domain.OrderId
import io.github.ticketing.payment.domain.Payment
import io.github.ticketing.payment.domain.PaymentStatus
import io.github.ticketing.payment.domain.TransitionResult
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * 결제 행 저장(트랜잭션 A) → PG 호출(트랜잭션 밖) → 결과 반영(트랜잭션 B)으로 나눈다.
 * PG 호출 중에 DB 커넥션과 락을 붙들지 않기 위해서다.
 */
@Service
class ProcessPaymentService(
    private val payments: PaymentCommandPort,
    private val pg: PgPort,
    private val events: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) : ProcessPaymentUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun process(command: ProcessPaymentCommand) {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "PG 호출은 트랜잭션 밖에서 해야 한다" }
        val orderId = OrderId(command.orderId)
        val payment = requireNotNull(tx.execute { payments.findByOrderId(orderId) ?: payments.save(newPayment(command)) })
        val paymentId = requireNotNull(payment.id).value
        // PG 거래가 이미 붙었으면 접수된 것이다. Unknown으로 끝난 REQUESTED만 같은 멱등 키로 다시 보낸다
        if (payment.status != PaymentStatus.REQUESTED || payment.pgTransactionId != null) {
            log.info("이미 PG에 접수된 결제라 다시 요청하지 않는다 paymentId={} orderId={} status={}", paymentId, orderId.value, payment.status)
            return
        }

        val request = PgPaymentRequest(orderId.value.toString(), payment.amount, payment.idempotencyKey)
        when (val result = pg.requestPayment(request)) {
            is PgRequestResult.Accepted -> tx.executeWithoutResult { attach(orderId, result.transactionId) }
            is PgRequestResult.Rejected -> tx.executeWithoutResult { fail(orderId, result.reason) }
            // 실패로 확정하면 좌석은 반환됐는데 돈은 빠져나갈 수 있다. 웹훅이나 결제 대사(M2)가 결과를 맞춘다
            is PgRequestResult.Unknown ->
                log.warn("PG 결제 요청 결과를 알 수 없어 REQUESTED로 둔다 paymentId={} orderId={} cause={}", paymentId, orderId.value, result.cause)
        }
    }

    private fun newPayment(command: ProcessPaymentCommand) = Payment.request(
        OrderId(command.orderId), MemberId(command.memberId), command.amount, command.idempotencyKey, clock.instant(),
    )

    private fun attach(orderId: OrderId, transactionId: String) {
        val payment = requireNotNull(payments.findByOrderId(orderId))
        // 승인 웹훅이 먼저 와서 거래를 붙였을 수 있다
        if (payment.pgTransactionId == transactionId) return
        payment.attachPgTransaction(transactionId)
        payments.save(payment)
        log.info("PG 결제 접수 paymentId={} orderId={} transactionId={}", payment.id?.value, orderId.value, transactionId)
    }

    private fun fail(orderId: OrderId, reason: String) {
        val payment = requireNotNull(payments.findByOrderId(orderId))
        val paymentId = requireNotNull(payment.id).value
        val now = clock.instant()
        when (payment.fail(reason, now)) {
            TransitionResult.Applied -> {
                payments.save(payment)
                events.publishEvent(PaymentFailed(paymentId, orderId.value, reason, now))
                log.info("PG가 결제 요청을 거절했다 paymentId={} orderId={} reason={}", paymentId, orderId.value, reason)
            }
            TransitionResult.AlreadyApplied -> Unit
        }
    }
}
