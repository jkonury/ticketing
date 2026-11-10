package io.github.ticketing.payment.application.service

import io.github.ticketing.payment.application.port.provided.RefundPaymentUseCase
import io.github.ticketing.payment.application.port.required.PaymentCommandPort
import io.github.ticketing.payment.application.port.required.PgPort
import io.github.ticketing.payment.application.port.required.PgRequestResult
import io.github.ticketing.payment.domain.InvalidPaymentStateException
import io.github.ticketing.payment.domain.OrderId
import io.github.ticketing.payment.domain.PaymentStatus
import io.github.ticketing.payment.domain.TransitionResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * 환불 대상 확인(트랜잭션) → PG 환불 요청(트랜잭션 밖) → PG가 접수하면 REFUND_REQUESTED 저장(트랜잭션).
 * 접수 전에 REFUND_REQUESTED로 바꾸면 PG 결과가 Unknown일 때 재처리가 "이미 요청함"으로 건너뛰어 환불이 영영 나가지 않는다.
 * 그래서 REFUND_REQUESTED는 "PG가 환불 요청을 접수했다"는 뜻이고, 그 상태에서는 PG를 다시 부르지 않는다.
 */
@Service
class RefundPaymentService(
    private val payments: PaymentCommandPort,
    private val pg: PgPort,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) : RefundPaymentUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Target(val paymentId: Long, val transactionId: String)

    override fun refund(orderId: Long, reason: String) {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "PG 호출은 트랜잭션 밖에서 해야 한다" }
        val target = tx.execute { findTarget(OrderId(orderId)) } ?: return

        when (val result = pg.requestRefund(target.transactionId, refundIdempotencyKey(orderId))) {
            is PgRequestResult.Accepted -> {
                tx.executeWithoutResult { markRefundRequested(OrderId(orderId)) }
                log.info("PG 환불 요청 접수 paymentId={} orderId={} reason={}", target.paymentId, orderId, reason)
            }
            // 4xx는 다시 보내도 같다. 사람이 확인해야 한다
            is PgRequestResult.Rejected ->
                log.error("PG가 환불 요청을 거절했다 paymentId={} orderId={} reason={}", target.paymentId, orderId, result.reason)
            // 환불은 같은 멱등 키로 다시 보내도 안전하다. 던져서 이벤트 발행 레지스트리의 재처리 대상으로 남긴다
            is PgRequestResult.Unknown -> {
                log.warn("PG 환불 요청 결과를 알 수 없다 paymentId={} orderId={} cause={}", target.paymentId, orderId, result.cause)
                throw PgResultUnknownException("환불", orderId, result.cause)
            }
        }
    }

    private fun findTarget(orderId: OrderId): Target? {
        val payment = payments.findByOrderId(orderId)
        if (payment == null) {
            log.warn("환불할 결제가 없다 orderId={}", orderId.value)
            return null
        }
        val paymentId = requireNotNull(payment.id).value
        return when (payment.status) {
            PaymentStatus.SUCCEEDED -> Target(paymentId, checkNotNull(payment.pgTransactionId) { "승인된 결제에 PG 거래가 없다 paymentId=$paymentId" })
            PaymentStatus.REFUND_REQUESTED, PaymentStatus.REFUNDED -> {
                log.info("이미 환불을 요청했다 paymentId={} orderId={} status={}", paymentId, orderId.value, payment.status)
                null
            }
            // 아직 승인 전이면 나중에 승인된 뒤 재처리돼야 하므로 던진다
            PaymentStatus.REQUESTED, PaymentStatus.FAILED -> throw InvalidPaymentStateException(payment.status, "requestRefund")
        }
    }

    private fun markRefundRequested(orderId: OrderId) {
        val payment = requireNotNull(payments.findByOrderId(orderId))
        // 환불 완료 웹훅이 먼저 왔으면 이미 REFUNDED다
        if (payment.status == PaymentStatus.REFUNDED) return
        when (payment.requestRefund(clock.instant())) {
            TransitionResult.Applied -> payments.save(payment)
            TransitionResult.AlreadyApplied -> Unit
        }
    }
}

/** 주문 하나에 환불 하나가 되도록 PG 멱등 키를 주문 ID로 고정한다. */
internal fun refundIdempotencyKey(orderId: Long) = "refund-$orderId"
