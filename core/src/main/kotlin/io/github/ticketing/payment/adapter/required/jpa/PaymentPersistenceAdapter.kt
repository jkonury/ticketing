package io.github.ticketing.payment.adapter.required.jpa

import io.github.ticketing.payment.application.port.required.PaymentCommandPort
import io.github.ticketing.payment.domain.OrderId
import io.github.ticketing.payment.domain.Payment
import org.jooq.DSLContext
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Repository
import ticketing.jooq.payment.tables.references.WEBHOOK_EVENT
import java.time.Instant
import java.time.ZoneOffset

@Repository
internal class PaymentPersistenceAdapter(
    private val payments: PaymentJpaRepository,
    private val dsl: DSLContext,
) : PaymentCommandPort {
    override fun save(payment: Payment): Payment {
        val id = payment.id ?: return payments.saveAndFlush(payment.toNewEntity()).toDomain()
        val entity = payments.findById(id.value).orElseThrow { IllegalStateException("결제를 찾을 수 없다: ${id.value}") }
        // 도메인이 읽은 시점의 버전과 다르면 그 사이 다른 트랜잭션이 갱신한 것이다
        if (entity.version != payment.version) {
            throw ObjectOptimisticLockingFailureException(PaymentJpaEntity::class.java, id.value)
        }
        entity.applyChanges(payment)
        return payments.saveAndFlush(entity).toDomain()
    }

    override fun findByOrderId(orderId: OrderId): Payment? = payments.findByOrderId(orderId.value)?.toDomain()

    override fun findByPgTransactionId(transactionId: String): Payment? =
        payments.findByPgTransactionId(transactionId)?.toDomain()

    // 유니크 위반 예외를 쓰면 PostgreSQL 트랜잭션이 abort되어 이후 쓰기가 막힌다
    override fun recordWebhookEvent(eventId: String, type: String, transactionId: String, now: Instant): Boolean =
        dsl.insertInto(WEBHOOK_EVENT, WEBHOOK_EVENT.EVENT_ID, WEBHOOK_EVENT.TYPE, WEBHOOK_EVENT.TRANSACTION_ID, WEBHOOK_EVENT.RECEIVED_AT)
            .values(eventId, type, transactionId, now.atOffset(ZoneOffset.UTC))
            .onConflict(WEBHOOK_EVENT.EVENT_ID)
            .doNothing()
            .execute() == 1
}
