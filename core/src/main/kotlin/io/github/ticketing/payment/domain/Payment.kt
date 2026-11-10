package io.github.ticketing.payment.domain

import java.math.BigDecimal
import java.time.Instant

sealed interface TransitionResult {
    data object Applied : TransitionResult

    data object AlreadyApplied : TransitionResult
}

class Payment private constructor(
    val id: PaymentId?,
    val orderId: OrderId,
    val memberId: MemberId,
    val amount: BigDecimal,
    val idempotencyKey: String,
    val requestedAt: Instant,
    val version: Long,
    status: PaymentStatus,
    pgTransactionId: String?,
    failureReason: String?,
    approvedAt: Instant?,
    failedAt: Instant?,
    refundRequestedAt: Instant?,
    refundedAt: Instant?,
) {
    var status: PaymentStatus = status
        private set
    var pgTransactionId: String? = pgTransactionId
        private set
    var failureReason: String? = failureReason
        private set
    var approvedAt: Instant? = approvedAt
        private set
    var failedAt: Instant? = failedAt
        private set
    var refundRequestedAt: Instant? = refundRequestedAt
        private set
    var refundedAt: Instant? = refundedAt
        private set

    /** 같은 값이면 no-op. 다른 값이면 PG 거래가 둘로 갈라진 것이므로 예외다. */
    fun attachPgTransaction(transactionId: String) {
        val current = pgTransactionId
        if (current == null) {
            pgTransactionId = transactionId
        } else if (current != transactionId) {
            throw PgTransactionConflictException(current, transactionId)
        }
    }

    fun approve(amount: BigDecimal, now: Instant): TransitionResult = when (status) {
        PaymentStatus.SUCCEEDED -> TransitionResult.AlreadyApplied
        PaymentStatus.REQUESTED -> {
            if (this.amount.compareTo(amount) != 0) throw PaymentAmountMismatchException(this.amount, amount)
            status = PaymentStatus.SUCCEEDED
            approvedAt = now
            TransitionResult.Applied
        }
        else -> throw InvalidPaymentStateException(status, "approve")
    }

    fun fail(reason: String, now: Instant): TransitionResult = when (status) {
        PaymentStatus.FAILED -> TransitionResult.AlreadyApplied
        PaymentStatus.REQUESTED -> {
            status = PaymentStatus.FAILED
            failureReason = reason
            failedAt = now
            TransitionResult.Applied
        }
        else -> throw InvalidPaymentStateException(status, "fail")
    }

    fun requestRefund(now: Instant): TransitionResult = when (status) {
        PaymentStatus.REFUND_REQUESTED -> TransitionResult.AlreadyApplied
        PaymentStatus.SUCCEEDED -> {
            status = PaymentStatus.REFUND_REQUESTED
            refundRequestedAt = now
            TransitionResult.Applied
        }
        else -> throw InvalidPaymentStateException(status, "requestRefund")
    }

    fun completeRefund(now: Instant): TransitionResult = when (status) {
        PaymentStatus.REFUNDED -> TransitionResult.AlreadyApplied
        PaymentStatus.REFUND_REQUESTED, PaymentStatus.SUCCEEDED -> {
            status = PaymentStatus.REFUNDED
            refundedAt = now
            TransitionResult.Applied
        }
        else -> throw InvalidPaymentStateException(status, "completeRefund")
    }

    companion object {
        fun request(orderId: OrderId, memberId: MemberId, amount: BigDecimal, idempotencyKey: String, now: Instant) =
            Payment(
                id = null, orderId = orderId, memberId = memberId, amount = amount,
                idempotencyKey = idempotencyKey, requestedAt = now, version = 0,
                status = PaymentStatus.REQUESTED, pgTransactionId = null, failureReason = null,
                approvedAt = null, failedAt = null, refundRequestedAt = null, refundedAt = null,
            )

        /** 영속 계층이 저장된 상태를 복원할 때만 쓴다. */
        fun restore(
            id: PaymentId, orderId: OrderId, memberId: MemberId, amount: BigDecimal, idempotencyKey: String,
            requestedAt: Instant, version: Long, status: PaymentStatus, pgTransactionId: String?,
            failureReason: String?, approvedAt: Instant?, failedAt: Instant?,
            refundRequestedAt: Instant?, refundedAt: Instant?,
        ) = Payment(
            id, orderId, memberId, amount, idempotencyKey, requestedAt, version, status, pgTransactionId,
            failureReason, approvedAt, failedAt, refundRequestedAt, refundedAt,
        )
    }
}
