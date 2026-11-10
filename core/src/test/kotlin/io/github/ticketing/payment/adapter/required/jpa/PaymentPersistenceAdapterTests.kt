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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class PaymentPersistenceAdapterTests : IntegrationTestSupport() {
    @Autowired lateinit var port: PaymentCommandPort

    @Autowired lateinit var tx: TransactionTemplate

    private val now = Instant.parse("2026-11-10T10:00:00Z")
    private val ids = AtomicLong(System.nanoTime() % 1_000_000_000L * 100)

    private fun newPayment(orderId: Long = ids.incrementAndGet(), key: String = UUID.randomUUID().toString()) =
        Payment.request(OrderId(orderId), MemberId(7), BigDecimal(20_000), key, now)

    @Test
    fun `저장하고 조회하면 상태와 버전이 복원된다`() {
        val saved = tx.execute { port.save(newPayment()) }!!
        assertThat(saved.id).isNotNull()
        assertThat(saved.version).isEqualTo(0)

        val found = tx.execute { port.findByOrderId(saved.orderId) }!!
        assertThat(found.id).isEqualTo(saved.id)
        assertThat(found.amount).isEqualByComparingTo("20000")
        assertThat(found.status).isEqualTo(PaymentStatus.REQUESTED)
        assertThat(found.requestedAt).isEqualTo(now)
    }

    @Test
    fun `전이 후 저장하면 변경이 반영되고 PG 거래 ID로 조회된다`() {
        val saved = tx.execute { port.save(newPayment()) }!!
        val txId = "tx-" + UUID.randomUUID()
        saved.attachPgTransaction(txId)
        saved.approve(BigDecimal(20_000), now.plusSeconds(1))
        val updated = tx.execute { port.save(saved) }!!
        assertThat(updated.version).isEqualTo(1)

        val found = tx.execute { port.findByPgTransactionId(txId) }!!
        assertThat(found.status).isEqualTo(PaymentStatus.SUCCEEDED)
        assertThat(found.approvedAt).isEqualTo(now.plusSeconds(1))
    }

    @Test
    fun `오래된 버전으로 저장하면 낙관적 락 예외가 난다`() {
        val saved = tx.execute { port.save(newPayment()) }!!
        val stale = tx.execute { port.findByOrderId(saved.orderId) }!!
        saved.fail("x", now)
        tx.execute { port.save(saved) }

        stale.fail("y", now)
        assertThatThrownBy { tx.execute { port.save(stale) } }.isInstanceOf(ObjectOptimisticLockingFailureException::class.java)
    }

    @Test
    fun `같은 order_id를 두 번 저장하면 유니크 제약 위반이다`() {
        val orderId = ids.incrementAndGet()
        tx.execute { port.save(newPayment(orderId)) }
        assertThatThrownBy { tx.execute { port.save(newPayment(orderId)) } }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `웹훅 이벤트는 처음 받을 때만 true다`() {
        val eventId = UUID.randomUUID().toString()
        assertThat(tx.execute { port.recordWebhookEvent(eventId, "APPROVED", "tx-1", now) }).isTrue()
        assertThat(tx.execute { port.recordWebhookEvent(eventId, "APPROVED", "tx-1", now) }).isFalse()
    }
}
