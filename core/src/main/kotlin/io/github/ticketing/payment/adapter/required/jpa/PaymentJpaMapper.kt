package io.github.ticketing.payment.adapter.required.jpa

import io.github.ticketing.payment.domain.MemberId
import io.github.ticketing.payment.domain.OrderId
import io.github.ticketing.payment.domain.Payment
import io.github.ticketing.payment.domain.PaymentId
import io.github.ticketing.payment.domain.PaymentStatus

internal fun Payment.toNewEntity() = PaymentJpaEntity(
    orderId = orderId.value,
    memberId = memberId.value,
    amount = amount,
    status = status.name,
    idempotencyKey = idempotencyKey,
    pgTransactionId = pgTransactionId,
    failureReason = failureReason,
    requestedAt = requestedAt,
    approvedAt = approvedAt,
    failedAt = failedAt,
    refundRequestedAt = refundRequestedAt,
    refundedAt = refundedAt,
)

/** 상태 전이로 바뀔 수 있는 필드만 반영한다. */
internal fun PaymentJpaEntity.applyChanges(payment: Payment) {
    status = payment.status.name
    pgTransactionId = payment.pgTransactionId
    failureReason = payment.failureReason
    approvedAt = payment.approvedAt
    failedAt = payment.failedAt
    refundRequestedAt = payment.refundRequestedAt
    refundedAt = payment.refundedAt
}

internal fun PaymentJpaEntity.toDomain(): Payment = Payment.restore(
    id = PaymentId(requireNotNull(id)),
    orderId = OrderId(orderId),
    memberId = MemberId(memberId),
    amount = amount,
    idempotencyKey = idempotencyKey,
    requestedAt = requestedAt,
    version = version,
    status = PaymentStatus.valueOf(status),
    pgTransactionId = pgTransactionId,
    failureReason = failureReason,
    approvedAt = approvedAt,
    failedAt = failedAt,
    refundRequestedAt = refundRequestedAt,
    refundedAt = refundedAt,
)
