package io.github.ticketing.payment.adapter.required.jpa

import io.github.ticketing.payment.domain.*

internal fun Payment.toNewEntity() = PaymentJpaEntity(
    orderId.value, memberId.value, amount, idempotencyKey, requestedAt,
    status.name, pgTransactionId, failureReason, approvedAt, failedAt, refundRequestedAt, refundedAt,
)
internal fun PaymentJpaEntity.applyChanges(payment: Payment) {
    status = payment.status.name
    pgTransactionId = payment.pgTransactionId
    failureReason = payment.failureReason
    approvedAt = payment.approvedAt
    failedAt = payment.failedAt
    refundRequestedAt = payment.refundRequestedAt
    refundedAt = payment.refundedAt
}
internal fun PaymentJpaEntity.toDomain() = Payment.restore(
    PaymentId(requireNotNull(id)), OrderId(orderId), MemberId(memberId), amount, idempotencyKey, requestedAt, version,
    PaymentStatus.valueOf(status), pgTransactionId, failureReason, approvedAt, failedAt, refundRequestedAt, refundedAt,
)
