package io.github.ticketing.payment.adapter.required.jpa

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.payment.application.port.required.PaymentCommandPort
import io.github.ticketing.payment.domain.MemberId
import io.github.ticketing.payment.domain.OrderId
import io.github.ticketing.payment.domain.Payment
import io.github.ticketing.payment.domain.PaymentStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class PaymentPersistenceTests(private val port: PaymentCommandPort, private val tx: TransactionTemplate) : IntegrationTestSupport() {

    private val now = Instant.parse("2026-11-11T00:00:00Z")
    private val ids = AtomicLong(System.nanoTime() % 1_000_000_000L * 100)

    private fun newPayment(order: Long = ids.incrementAndGet(), key: String = UUID.randomUUID().toString()) =
        Payment.request(OrderId(order), MemberId(1), BigDecimal("100"), key, now)

    private fun save(payment: Payment) = requireNotNull(tx.execute { port.save(payment) })

    @Test
    fun `저장 조회와 상태 갱신은 모든 값을 복원한다`() {
        val original = newPayment()
        original.attachPgTransaction(UUID.randomUUID().toString())
        original.approve(BigDecimal("100"), now.plusSeconds(1))
        original.requestRefund(now.plusSeconds(2))
        original.completeRefund(now.plusSeconds(3))

        val saved = save(original)
        val found = requireNotNull(tx.execute { port.findByOrderId(saved.orderId) })

        assertThat(found.id).isEqualTo(saved.id)
        assertThat(found.amount).isEqualByComparingTo("100")
        assertThat(found.memberId).isEqualTo(saved.memberId)
        assertThat(found.status).isEqualTo(PaymentStatus.REFUNDED)
        assertThat(found.requestedAt).isEqualTo(now)
        assertThat(found.approvedAt).isEqualTo(now.plusSeconds(1))
        assertThat(found.refundRequestedAt).isEqualTo(now.plusSeconds(2))
        assertThat(found.refundedAt).isEqualTo(now.plusSeconds(3))
        assertThat(tx.execute { port.findByIdempotencyKey(saved.idempotencyKey) }?.id).isEqualTo(saved.id)
        assertThat(tx.execute { port.findByPgTransactionId(requireNotNull(saved.pgTransactionId)) }?.id).isEqualTo(saved.id)
    }

    @Test
    fun `주문 멱등 키 거래 ID는 각각 유일하다`() {
        val order = ids.incrementAndGet()
        val key = UUID.randomUUID().toString()
        val transaction = UUID.randomUUID().toString()
        val existing = newPayment(order, key)
        existing.attachPgTransaction(transaction)
        save(existing)

        assertThatThrownBy { save(newPayment(order)) }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { save(newPayment(key = key)) }.isInstanceOf(DataIntegrityViolationException::class.java)

        val duplicate = newPayment()
        duplicate.attachPgTransaction(transaction)

        assertThatThrownBy { save(duplicate) }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `오래된 버전 저장은 거부된다`() {
        val saved = save(newPayment())
        val firstLoaded = requireNotNull(tx.execute { port.findByOrderId(saved.orderId) })
        val secondLoaded = requireNotNull(tx.execute { port.findByOrderId(saved.orderId) })

        firstLoaded.approve(BigDecimal("100"), now)
        val updated = save(firstLoaded)

        assertThat(updated.version).isEqualTo(requireNotNull(saved.version) + 1)

        secondLoaded.fail("failed", now)

        assertThatThrownBy { save(secondLoaded) }.isInstanceOf(ObjectOptimisticLockingFailureException::class.java)
    }

    @Test
    fun `웹훅 중복은 거부되고 롤백된 기록은 다시 저장된다`() {
        val event = UUID.randomUUID().toString()

        tx.execute { status ->
            assertThat(port.recordWebhookEvent(event, "APPROVED", "tx", now)).isTrue()
            status.setRollbackOnly()
        }

        assertThat(tx.execute { port.recordWebhookEvent(event, "APPROVED", "tx", now) }).isTrue()
        assertThat(tx.execute { port.recordWebhookEvent(event, "APPROVED", "tx", now) }).isFalse()
    }
}
