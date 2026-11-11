package io.github.ticketing.payment.adapter.provided

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.payment.PaymentRequested
import io.github.ticketing.payment.RefundRequested
import io.github.ticketing.payment.application.port.required.PaymentCommandPort
import io.github.ticketing.payment.application.port.required.PgPaymentRequest
import io.github.ticketing.payment.application.port.required.PgPort
import io.github.ticketing.payment.application.port.required.PgRequestResult
import io.github.ticketing.payment.domain.MemberId
import io.github.ticketing.payment.domain.OrderId
import io.github.ticketing.payment.domain.Payment
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Import(PaymentListenerTests.FixedClock::class)
class PaymentListenerTests : IntegrationTestSupport() {

    @TestConfiguration(proxyBeanMethods = false)
    class FixedClock {

        @Bean
        @Primary
        fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC)

        @Bean
        @Primary
        fun testPg() = TestPg()
    }

    class TestPg : PgPort {

        val flags = ConcurrentHashMap<String, Boolean>()

        override fun requestPayment(request: PgPaymentRequest): PgRequestResult {
            flags[request.orderId] = TransactionSynchronizationManager.isActualTransactionActive()
            return if (request.idempotencyKey.startsWith("unknown")) PgRequestResult.Unknown("timeout") else PgRequestResult.Accepted("tx-${request.orderId}")
        }

        override fun requestRefund(transactionId: String, idempotencyKey: String): PgRequestResult {
            flags[transactionId] = TransactionSynchronizationManager.isActualTransactionActive()
            return if (transactionId.startsWith("reject")) PgRequestResult.Rejected("rejected") else PgRequestResult.Accepted(transactionId)
        }
    }

    @Autowired
    lateinit var pg: TestPg

    @Autowired
    lateinit var payments: PaymentCommandPort

    @Autowired
    lateinit var tx: TransactionTemplate

    @Autowired
    lateinit var publisher: ApplicationEventPublisher

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val now = Instant.parse("2026-11-11T00:00:00Z")

    private fun publish(event: Any) {
        tx.executeWithoutResult { publisher.publishEvent(event) }
    }

    private fun assertPublication(listener: String, order: Long, status: String) {
        await().atMost(10, TimeUnit.SECONDS).untilAsserted {
            val rows = jdbc.queryForList("select status, completion_date from event_publication where listener_id like ? and serialized_event like ?", "%$listener%", "%\"orderId\":$order%")
            assertThat(rows).hasSize(1)
            assertThat(rows.single()["status"]).isEqualTo(status)
            if (status == "COMPLETED") assertThat(rows.single()["completion_date"]).isNotNull() else assertThat(rows.single()["completion_date"]).isNull()
        }
    }

    @Test
    fun `결제 리스너는 트랜잭션 밖에서 PG를 호출하고 성공과 미확정을 레지스트리에 기록한다`() {
        val first = System.nanoTime()
        val second = first + 1

        publish(PaymentRequested(first, 1, BigDecimal("100"), "ok-${UUID.randomUUID()}", now))
        publish(PaymentRequested(second, 1, BigDecimal("100"), "unknown-${UUID.randomUUID()}", now))

        assertPublication("PaymentRequestListener", first, "COMPLETED")
        assertPublication("PaymentRequestListener", second, "FAILED")
        assertThat(pg.flags).containsEntry(first.toString(), false).containsEntry(second.toString(), false)
    }

    @Test
    fun `환불 리스너는 트랜잭션 밖에서 PG를 호출하고 접수와 거절을 레지스트리에 기록한다`() {
        val first = System.nanoTime()
        val second = first + 1
        fun prepare(order: Long, transaction: String) {
            tx.executeWithoutResult {
                val approvedPayment = Payment.request(OrderId(order), MemberId(1), BigDecimal("100"), UUID.randomUUID().toString(), now)
                approvedPayment.attachPgTransaction(transaction)
                approvedPayment.approve(BigDecimal("100"), now)
                payments.save(approvedPayment)
            }
        }
        val ok = "ok-$first"
        val reject = "reject-$second"

        prepare(first, ok)
        prepare(second, reject)

        publish(RefundRequested(first, "test", now))
        publish(RefundRequested(second, "test", now))

        assertPublication("RefundRequestListener", first, "COMPLETED")
        assertPublication("RefundRequestListener", second, "FAILED")
        assertThat(pg.flags).containsEntry(ok, false).containsEntry(reject, false)
    }
}
