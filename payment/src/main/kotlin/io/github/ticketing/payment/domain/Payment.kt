package io.github.ticketing.payment.domain

import java.math.BigDecimal
import java.time.Instant

@JvmInline
value class PaymentId(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
value class OrderId(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
value class MemberId(val value: Long) {
    init {
        require(value > 0)
    }
}

enum class PaymentStatus { REQUESTED, SUCCEEDED, FAILED, REFUND_REQUESTED, REFUNDED }

sealed interface TransitionResult {
    data object Applied : TransitionResult
    data object AlreadyApplied : TransitionResult
}

open class PaymentRuleException(val code: String, message: String = code) : RuntimeException(message)

class InvalidPaymentStateException(status: PaymentStatus) : PaymentRuleException("INVALID_PAYMENT_STATE", "INVALID_PAYMENT_STATE: $status")

class PaymentContractMismatchException : PaymentRuleException("PAYMENT_CONTRACT_MISMATCH")

class Payment private constructor(
    val id: PaymentId?,
    val orderId: OrderId,
    val memberId: MemberId,
    val amount: BigDecimal,
    val idempotencyKey: String,
    val requestedAt: Instant,
    val version: Long?,
    status: PaymentStatus,
    pgTransactionId: String?,
    failureReason: String?,
    approvedAt: Instant?,
    failedAt: Instant?,
    refundRequestedAt: Instant?,
    refundedAt: Instant?,
) {

    var status = status
        private set
    var pgTransactionId = pgTransactionId
        private set
    var failureReason = failureReason
        private set
    var approvedAt = approvedAt
        private set
    var failedAt = failedAt
        private set
    var refundRequestedAt = refundRequestedAt
        private set
    var refundedAt = refundedAt
        private set

    init {
        require(amount > BigDecimal.ZERO && amount.stripTrailingZeros().scale() <= 0 && amount <= BigDecimal("999999999999"))
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= MAX_IDENTIFIER_LENGTH)
        require(pgTransactionId == null || (pgTransactionId.isNotBlank() && pgTransactionId.length <= MAX_IDENTIFIER_LENGTH))
    }

    fun attachPgTransaction(transactionId: String) {
        require(transactionId.isNotBlank() && transactionId.length <= MAX_IDENTIFIER_LENGTH)
        if (pgTransactionId != null && pgTransactionId != transactionId) throw PaymentContractMismatchException()
        pgTransactionId = transactionId
    }

    fun approve(amount: BigDecimal, now: Instant): TransitionResult {
        if (this.amount.compareTo(amount) != 0) throw PaymentContractMismatchException()
        return when (status) {
            PaymentStatus.REQUESTED -> {
                status = PaymentStatus.SUCCEEDED
                approvedAt = now
                TransitionResult.Applied
            }

            PaymentStatus.SUCCEEDED, PaymentStatus.REFUND_REQUESTED, PaymentStatus.REFUNDED -> TransitionResult.AlreadyApplied

            PaymentStatus.FAILED -> throw InvalidPaymentStateException(status)
        }
    }

    fun fail(reason: String, now: Instant): TransitionResult = when (status) {
        PaymentStatus.REQUESTED -> {
            status = PaymentStatus.FAILED
            failureReason = reason
            failedAt = now
            TransitionResult.Applied
        }

        PaymentStatus.FAILED -> TransitionResult.AlreadyApplied

        PaymentStatus.SUCCEEDED, PaymentStatus.REFUND_REQUESTED, PaymentStatus.REFUNDED -> throw InvalidPaymentStateException(status)
    }

    fun requestRefund(now: Instant): TransitionResult = when (status) {
        PaymentStatus.SUCCEEDED -> {
            status = PaymentStatus.REFUND_REQUESTED
            refundRequestedAt = now
            TransitionResult.Applied
        }

        PaymentStatus.REFUND_REQUESTED, PaymentStatus.REFUNDED -> TransitionResult.AlreadyApplied

        PaymentStatus.REQUESTED, PaymentStatus.FAILED -> throw InvalidPaymentStateException(status)
    }

    fun completeRefund(now: Instant): TransitionResult = when (status) {
        PaymentStatus.REFUND_REQUESTED -> {
            status = PaymentStatus.REFUNDED
            refundedAt = now
            TransitionResult.Applied
        }

        PaymentStatus.REFUNDED -> TransitionResult.AlreadyApplied

        PaymentStatus.REQUESTED, PaymentStatus.SUCCEEDED, PaymentStatus.FAILED -> throw InvalidPaymentStateException(status)
    }

    companion object {
        fun request(orderId: OrderId, memberId: MemberId, amount: BigDecimal, idempotencyKey: String, now: Instant): Payment =
            Payment(null, orderId, memberId, amount, idempotencyKey, now, null, PaymentStatus.REQUESTED, null, null, null, null, null, null)

        fun restore(
            id: PaymentId,
            orderId: OrderId,
            memberId: MemberId,
            amount: BigDecimal,
            idempotencyKey: String,
            requestedAt: Instant,
            version: Long,
            status: PaymentStatus,
            pgTransactionId: String?,
            failureReason: String?,
            approvedAt: Instant?,
            failedAt: Instant?,
            refundRequestedAt: Instant?,
            refundedAt: Instant?,
        ): Payment = Payment(
            id, orderId, memberId, amount, idempotencyKey, requestedAt, version, status, pgTransactionId,
            failureReason, approvedAt, failedAt, refundRequestedAt, refundedAt,
        )
    }
}

private const val MAX_IDENTIFIER_LENGTH = 100
