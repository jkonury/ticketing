package io.github.ticketing.payment.adapter.provided

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.KafkaTestSupport
import io.github.ticketing.KafkaTopics
import io.github.ticketing.MutableClock
import io.github.ticketing.TestPg
import io.github.ticketing.payment.PaymentRequested
import io.github.ticketing.payment.RefundRequested
import io.github.ticketing.payment.application.port.provided.RequestPaymentCommand
import io.github.ticketing.payment.application.port.provided.RequestPaymentUseCase
import io.github.ticketing.payment.application.port.required.PaymentCommandPort
import io.github.ticketing.payment.domain.MemberId
import io.github.ticketing.payment.domain.OrderId
import io.github.ticketing.payment.domain.Payment
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 결제 리스너가 Kafka에서 받은 이벤트를 처리하는 경로(소비·멱등·재시도·DLT)를 실제 브로커로 확인한다. */
class PaymentListenerTests(
    private val pg: TestPg,
    private val requestPayment: RequestPaymentUseCase,
    private val clock: MutableClock,
    private val payments: PaymentCommandPort,
    private val tx: TransactionTemplate,
    private val kafka: KafkaTemplate<String, ByteArray>,
    private val json: JsonMapper,
    private val jdbc: JdbcTemplate,
    private val topics: KafkaTopics,
    private val kafkaProperties: KafkaProperties,
    private val meters: MeterRegistry,
) : IntegrationTestSupport() {

    private val now = Instant.parse("2026-11-11T00:00:00Z")

    @BeforeEach
    fun fixClock() {
        clock.now = now
    }

    private fun publish(event: PaymentRequested) = send(topics.paymentRequested, event.orderId, event)

    private fun publish(event: RefundRequested) = send(topics.refundRequested, event.orderId, event)

    private fun send(topic: String, orderId: Long, event: Any) {
        kafka.send(topic, orderId.toString(), json.writeValueAsBytes(event)).get(10, TimeUnit.SECONDS)
    }

    private fun requestEvent(order: Long, key: String = "ok-${UUID.randomUUID()}") = PaymentRequested(order, 1, BigDecimal("100"), key, now)

    private fun pgTransactionOf(order: Long): String? =
        jdbc.queryForList("select pg_transaction_id from payment.payment where order_id = ?", String::class.java, order).firstOrNull()

    private fun deadLetters(topic: String, order: Long) =
        KafkaTestSupport.recordsOf(kafkaProperties.bootstrapServers, topics.deadLetterOf(topic), order.toString())

    private fun deadLetterCount(topic: String) = meters.counter("ticketing.kafka.dead.letter", "topic", topic).count()

    private fun prepareApprovedPayment(order: Long, transaction: String) {
        tx.executeWithoutResult {
            val approved = Payment.request(OrderId(order), MemberId(1), BigDecimal("100"), UUID.randomUUID().toString(), now)
            approved.attachPgTransaction(transaction)
            approved.approve(BigDecimal("100"), now)
            payments.save(approved)
        }
    }

    @Test
    fun `결제 요청 이벤트를 Kafka에서 받아 트랜잭션 밖에서 PG를 호출하고 거래를 연결한다`() {
        val order = System.nanoTime()

        publish(requestEvent(order))

        await().atMost(Duration.ofSeconds(15)).untilAsserted { assertThat(pgTransactionOf(order)).isEqualTo("tx-$order") }
        assertThat(pg.flags).containsEntry(order.toString(), false)
    }

    @Test
    fun `같은 결제 요청을 여러 번 받아도 결제와 PG 거래는 하나다`() {
        val order = System.nanoTime()
        val event = requestEvent(order)

        repeat(3) { publish(event) }

        await().atMost(Duration.ofSeconds(15)).untilAsserted { assertThat(pgTransactionOf(order)).isNotNull() }
        KafkaTestSupport.awaitConsumed(kafkaProperties.bootstrapServers, topics.paymentRequested, order.toString(), "ticketing-payment", 3)
        assertThat(deadLetters(topics.paymentRequested, order)).isEmpty()
        assertThat(pg.requests.count { it.orderId == order.toString() }).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from payment.payment where order_id = ?", Int::class.java, order)).isEqualTo(1)
    }

    @Test
    fun `PG 응답 전 같은 결제 요청이 다른 스레드에 도착해도 하나의 거래와 결제로 수렴한다`() {
        val order = System.nanoTime()
        val event = requestEvent(order)
        val command = RequestPaymentCommand(event.orderId, event.memberId, event.amount, event.idempotencyKey, event.requestedAt)
        val gate = CountDownLatch(1)
        pg.gate = gate
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            publish(event)
            await().atMost(Duration.ofSeconds(10)).untilAsserted {
                assertThat(pg.requests.count { it.orderId == order.toString() }).isEqualTo(1)
            }

            val duplicate = executor.submit {
                try {
                    requestPayment.request(command)
                } catch (_: ObjectOptimisticLockingFailureException) {
                    requestPayment.request(command)
                }
            }
            await().atMost(Duration.ofSeconds(10)).untilAsserted {
                assertThat(pg.requests.count { it.orderId == order.toString() }).isEqualTo(2)
            }
            gate.countDown()
            duplicate.get(15, TimeUnit.SECONDS)
            KafkaTestSupport.awaitConsumed(
                kafkaProperties.bootstrapServers,
                topics.paymentRequested,
                order.toString(),
                "ticketing-payment",
                1,
            )

            assertThat(deadLetters(topics.paymentRequested, order)).isEmpty()
            assertThat(pg.transactions).containsOnlyKeys(event.idempotencyKey)
            assertThat(pgTransactionOf(order)).isEqualTo("tx-$order")
            assertThat(jdbc.queryForObject("select count(*) from payment.payment where order_id = ?", Int::class.java, order)).isEqualTo(1)
        } finally {
            gate.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `PG 응답이 미확정이면 재시도하고 소진하면 DLT로 보낸다`() {
        val order = System.nanoTime()
        val before = deadLetterCount(topics.paymentRequested)

        publish(requestEvent(order, "unknown-${UUID.randomUUID()}"))

        await().atMost(Duration.ofSeconds(20)).untilAsserted { assertThat(deadLetters(topics.paymentRequested, order)).hasSize(1) }
        // 처음 시도 1회 + 재시도 3회, 모두 같은 멱등 키다
        assertThat(pg.requests.filter { it.orderId == order.toString() }.map { it.idempotencyKey }.toSet()).hasSize(1)
        assertThat(pg.requests.count { it.orderId == order.toString() }).isEqualTo(4)
        assertThat(deadLetterCount(topics.paymentRequested)).isEqualTo(before + 1)
        assertThat(deadLetters(topics.paymentRequested, order).single().value()).contains("\"orderId\":$order")
    }

    @Test
    fun `PG가 결제를 거절하면 결제 실패를 기록하고 결과를 한 번 발행한다`() {
        val order = System.nanoTime()
        pg.paymentResult = "rejected"

        publish(requestEvent(order))
        KafkaTestSupport.awaitConsumed(kafkaProperties.bootstrapServers, topics.paymentRequested, order.toString(), "ticketing-payment", 1)

        assertThat(
            jdbc.queryForObject("select status from payment.payment where order_id = ?", String::class.java, order),
        ).isEqualTo("FAILED")
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(KafkaTestSupport.recordsOf(kafkaProperties.bootstrapServers, topics.paymentFailed, order.toString())).hasSize(1)
        }
    }

    @Test
    fun `재시도 중에 PG가 회복되면 같은 멱등 키로 복구하고 DLT로 보내지 않는다`() {
        val order = System.nanoTime()
        pg.paymentResult = "unknown"

        publish(requestEvent(order))
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(pg.requests.count { it.orderId == order.toString() }).isGreaterThanOrEqualTo(1)
        }
        pg.paymentResult = "accepted"

        await().atMost(Duration.ofSeconds(15)).untilAsserted { assertThat(pgTransactionOf(order)).isNotNull() }
        assertThat(pg.requests.filter { it.orderId == order.toString() }.map { it.idempotencyKey }.toSet()).hasSize(1)
        assertThat(deadLetters(topics.paymentRequested, order)).isEmpty()
    }

    @Test
    fun `환불 요청 이벤트를 Kafka에서 받아 트랜잭션 밖에서 PG 환불을 요청한다`() {
        val order = System.nanoTime()
        prepareApprovedPayment(order, "ok-$order")

        publish(RefundRequested(order, "test", now))

        await().atMost(Duration.ofSeconds(15)).untilAsserted { assertThat(pg.refunds).contains("ok-$order" to "refund-$order") }
        assertThat(pg.flags).containsEntry("ok-$order", false)
    }

    @Test
    fun `환불이 진행 중일 때 같은 환불 요청이 또 와도 같은 PG 멱등 키로만 요청한다`() {
        val order = System.nanoTime()
        prepareApprovedPayment(order, "ok-$order")
        val event = RefundRequested(order, "test", now)

        repeat(3) { publish(event) }

        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(pg.refunds.count { it.first == "ok-$order" }).isGreaterThanOrEqualTo(1)
        }
        KafkaTestSupport.awaitConsumed(kafkaProperties.bootstrapServers, topics.refundRequested, order.toString(), "ticketing-payment", 3)
        assertThat(pg.refunds.filter { it.first == "ok-$order" }.map { it.second }.toSet()).containsExactly("refund-$order")
        assertThat(jdbc.queryForObject("select status from payment.payment where order_id = ?", String::class.java, order))
            .isEqualTo("REFUND_REQUESTED")
        assertThat(deadLetters(topics.refundRequested, order)).isEmpty()
    }

    @Test
    fun `환불 거절과 미확정 이후에도 재시도는 같은 멱등 키로 복구한다`() {
        for (failure in listOf("rejected", "unknown")) {
            val order = System.nanoTime()
            val transaction = "ok-$order"
            prepareApprovedPayment(order, transaction)
            pg.refundResult = failure

            publish(RefundRequested(order, "test", now))
            await().atMost(Duration.ofSeconds(10)).untilAsserted {
                assertThat(pg.refunds).contains(transaction to "refund-$order")
            }
            pg.refundResult = "accepted"
            KafkaTestSupport.awaitConsumed(
                kafkaProperties.bootstrapServers,
                topics.refundRequested,
                order.toString(),
                "ticketing-payment",
                1,
            )

            assertThat(pg.refunds.filter { it.first == transaction }.map { it.second })
                .hasSizeGreaterThanOrEqualTo(2)
                .containsOnly("refund-$order")
            assertThat(deadLetters(topics.refundRequested, order)).isEmpty()
        }
    }

    @Test
    fun `환불을 PG가 거절하면 재시도하고 소진하면 DLT로 보낸다`() {
        val order = System.nanoTime()
        prepareApprovedPayment(order, "reject-$order")

        publish(RefundRequested(order, "test", now))

        await().atMost(Duration.ofSeconds(20)).untilAsserted { assertThat(deadLetters(topics.refundRequested, order)).hasSize(1) }
        assertThat(pg.refunds.count { it.first == "reject-$order" }).isEqualTo(4)
    }
}
