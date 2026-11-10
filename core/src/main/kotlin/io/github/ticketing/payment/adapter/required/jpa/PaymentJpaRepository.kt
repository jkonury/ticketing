package io.github.ticketing.payment.adapter.required.jpa

import org.springframework.data.jpa.repository.JpaRepository

internal interface PaymentJpaRepository : JpaRepository<PaymentJpaEntity, Long> {
    fun findByOrderId(orderId: Long): PaymentJpaEntity?

    fun findByPgTransactionId(pgTransactionId: String): PaymentJpaEntity?
}
