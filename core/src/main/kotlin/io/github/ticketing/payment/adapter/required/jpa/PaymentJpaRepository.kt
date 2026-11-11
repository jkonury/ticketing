package io.github.ticketing.payment.adapter.required.jpa

import org.springframework.data.repository.Repository
import java.util.Optional

interface PaymentJpaRepository : Repository<PaymentJpaEntity, Long> {
    fun findById(id: Long): Optional<PaymentJpaEntity>

    fun <S : PaymentJpaEntity> saveAndFlush(entity: S): S

    fun findByOrderId(orderId: Long): PaymentJpaEntity?
    fun findByIdempotencyKey(idempotencyKey: String): PaymentJpaEntity?
    fun findByPgTransactionId(pgTransactionId: String): PaymentJpaEntity?
}
