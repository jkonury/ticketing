package io.github.ticketing.fakepg.payment

import io.github.ticketing.fakepg.payment.config.FakePgProperties
import io.github.ticketing.fakepg.payment.service.IdempotencyKeyReusedException
import io.github.ticketing.fakepg.payment.service.PaymentCommand
import io.github.ticketing.fakepg.payment.service.PaymentService
import io.github.ticketing.fakepg.payment.service.Scenario
import io.github.ticketing.fakepg.payment.service.ScenarioResolver
import io.github.ticketing.fakepg.payment.service.TransactionNotFoundException
import io.github.ticketing.fakepg.payment.service.TransactionNotRefundableException
import io.github.ticketing.fakepg.payment.service.TransactionStatus
import io.github.ticketing.fakepg.payment.service.TransactionStore
import io.github.ticketing.fakepg.payment.service.WebhookScheduler
import io.github.ticketing.fakepg.payment.service.WebhookSender
import io.github.ticketing.fakepg.payment.service.WebhookTransport
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.random.RandomGenerator
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaymentServiceTest {
    private val now = Instant.parse("2030-01-01T16:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val properties = FakePgProperties(FakePgProperties.Webhook("http://localhost", "secret"))
    private val scheduler = FakeScheduler()
    private val transport = FakeTransport()
    private val mapper = JsonMapper.builder().build()
    private val sender = WebhookSender(properties, clock, mapper, transport, scheduler)
    private val store = TransactionStore()
    private val service = PaymentService(store, ScenarioResolver(properties, RandomGenerator.getDefault()), sender, properties, clock)

    @Test
    fun `각 결제 시나리오는 지정된 지연 뒤에 상태와 웹훅을 결정한다`() {
        val cases = listOf(
            Triple(Scenario.SUCCESS, TransactionStatus.APPROVED, "PAYMENT_APPROVED"),
            Triple(Scenario.FAIL, TransactionStatus.FAILED, "PAYMENT_FAILED"),
            Triple(Scenario.TIMEOUT, TransactionStatus.APPROVED, "PAYMENT_APPROVED"),
            Triple(Scenario.DELAYED, TransactionStatus.APPROVED, "PAYMENT_APPROVED"),
            Triple(Scenario.DUPLICATE_WEBHOOK, TransactionStatus.APPROVED, "PAYMENT_APPROVED"),
        )
        cases.forEach { (scenario, status, eventType) ->
            val registration = service.requestPayment(scenario.name, PaymentCommand(scenario.name, 100, scenario))

            assertEquals(TransactionStatus.PENDING, registration.transaction.status)
            assertEquals(
                if (scenario ==
                    Scenario.DELAYED
                ) {
                    properties.behavior.delayedWebhookDelay
                } else {
                    properties.webhook.delay
                },
                scheduler.nextDelay(),
            )
            scheduler.runNext()
            assertEquals(status, service.findTransactions(LocalDate.parse("2030-01-02")).single { it.orderId == scenario.name }.status)
            scheduler.runAll()
            val bodies = transport.bodies.toList()
            assertEquals(if (scenario == Scenario.DUPLICATE_WEBHOOK) 3 else 1, bodies.size)
            assertEquals(1, bodies.distinct().size)
            val event = mapper.readTree(bodies.first())
            assertEquals(eventType, event.get("type").asString())
            if (scenario == Scenario.FAIL) assertEquals("CARD_DECLINED", event.get("failureReason").asString())
            transport.bodies.clear()
        }
    }

    @Test
    fun `결제 멱등 키 재사용은 기존 거래를 반환하고 다른 요청은 거부한다`() {
        val command = PaymentCommand("order", 100, Scenario.SUCCESS)
        val first = service.requestPayment("key", command)
        val second = service.requestPayment("key", command)

        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(first.transaction, second.transaction)
        assertFailsWith<IdempotencyKeyReusedException> { service.requestPayment("key", command.copy(amount = 200)) }
        scheduler.runAll()
        assertEquals(1, transport.bodies.size)
    }

    @Test
    fun `승인 거래 환불은 멱등이며 미승인과 없는 거래는 거부한다`() {
        val approved = service.requestPayment("approved", PaymentCommand("approved", 100, Scenario.SUCCESS)).transaction
        val pending = service.requestPayment("pending", PaymentCommand("pending", 100, Scenario.SUCCESS)).transaction
        assertFailsWith<TransactionNotRefundableException> { service.refund(pending.transactionId, "refund") }
        assertFailsWith<TransactionNotFoundException> { service.refund("missing", "refund") }
        scheduler.runAll()
        transport.bodies.clear()

        val first = service.refund(approved.transactionId, "refund")
        val second = service.refund(approved.transactionId, "refund")

        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(TransactionStatus.REFUNDED, first.transaction.status)
        assertFailsWith<TransactionNotRefundableException> { service.refund(approved.transactionId, "other") }
        scheduler.runAll()
        assertEquals("REFUND_COMPLETED", mapper.readTree(transport.bodies.single()).get("type").asString())
    }

    @Test
    fun `거래 내역은 한국 날짜로 조회한다`() {
        service.requestPayment("key", PaymentCommand("order", 100, Scenario.SUCCESS))

        assertTrue(service.findTransactions(LocalDate.parse("2030-01-01")).isEmpty())
        assertEquals("order", service.findTransactions(LocalDate.parse("2030-01-02")).single().orderId)
        assertTrue(service.findTransactions(LocalDate.parse("2030-01-03")).isEmpty())
    }

    @Test
    fun `실패한 웹훅은 같은 본문으로 재시도하고 성공하면 멈춘다`() {
        transport.statuses.addAll(listOf(500, 503, 204))
        service.requestPayment("key", PaymentCommand("order", 100, Scenario.SUCCESS))
        scheduler.runNext()
        scheduler.runNext()

        assertEquals(properties.webhook.retryDelay, scheduler.nextDelay())
        scheduler.runAll()
        assertEquals(3, transport.bodies.size)
        assertEquals(1, transport.bodies.distinct().size)
    }

    @Test
    fun `웹훅이 계속 실패하면 최대 횟수에서 멈춘다`() {
        transport.statuses.addAll(listOf(500, 500, 500, 200))
        service.requestPayment("key", PaymentCommand("order", 100, Scenario.SUCCESS))

        scheduler.runAll()

        assertEquals(properties.webhook.maxAttempts, transport.bodies.size)
        assertEquals(1, transport.statuses.size)
    }

    private class FakeScheduler : WebhookScheduler {
        private val tasks = ArrayDeque<Pair<Duration, () -> Unit>>()

        override fun scheduleAfter(delay: Duration, task: () -> Unit) {
            tasks.addLast(delay to task)
        }

        fun nextDelay(): Duration = tasks.first().first
        fun runNext() = tasks.removeFirst().second()
        fun runAll() {
            while (tasks.isNotEmpty()) runNext()
        }
    }

    private class FakeTransport : WebhookTransport {
        val statuses = ArrayDeque<Int>()
        val bodies = mutableListOf<String>()

        override fun post(rawBody: String): Int {
            bodies.add(rawBody)
            return if (statuses.isEmpty()) 200 else statuses.removeFirst()
        }
    }
}
