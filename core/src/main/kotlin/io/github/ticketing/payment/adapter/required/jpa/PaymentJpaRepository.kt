package io.github.ticketing.payment.adapter.required.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface PaymentJpaRepository : JpaRepository<PaymentJpaEntity, Long> {
    fun findByOrderId(orderId: Long): PaymentJpaEntity?
    fun findByIdempotencyKey(idempotencyKey: String): PaymentJpaEntity?
    fun findByPgTransactionId(pgTransactionId: String): PaymentJpaEntity?
}
