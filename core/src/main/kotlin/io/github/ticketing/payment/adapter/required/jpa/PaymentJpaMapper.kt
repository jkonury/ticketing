package io.github.ticketing.payment.adapter.required.jpa

import io.github.ticketing.payment.domain.MemberId
import io.github.ticketing.payment.domain.OrderId
import io.github.ticketing.payment.domain.Payment
import io.github.ticketing.payment.domain.PaymentId
import io.github.ticketing.payment.domain.PaymentStatus

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
