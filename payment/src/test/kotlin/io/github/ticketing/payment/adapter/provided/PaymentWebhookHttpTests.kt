package io.github.ticketing.payment.adapter.provided

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.KafkaTestSupport
import io.github.ticketing.KafkaTopics
import io.github.ticketing.MutableClock
import io.github.ticketing.TEST_WEBHOOK_SECRET
import io.github.ticketing.TestPg
import io.github.ticketing.expectStatus
import io.github.ticketing.payment.application.port.provided.RequestPaymentCommand
import io.github.ticketing.payment.application.port.provided.RequestPaymentUseCase
import io.github.ticketing.payment.application.port.required.PaymentCommandPort
import io.github.ticketing.payment.domain.MemberId
import io.github.ticketing.payment.domain.OrderId
import io.github.ticketing.payment.domain.Payment
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PaymentWebhookHttpTests(
    private val mockMvcTester: MockMvcTester,
    private val payments: PaymentCommandPort,
    private val tx: TransactionTemplate,
    private val jdbc: JdbcTemplate,
    private val clock: MutableClock,
    private val topics: KafkaTopics,
    private val kafka: KafkaProperties,
    private val pg: TestPg,
    private val requestPayment: RequestPaymentUseCase,
) : IntegrationTestSupport() {

    private val now = Instant.parse("2026-11-11T00:00:00Z")
    private val path = "/api/v1/payments/webhooks/fake-pg"

    @BeforeEach
    fun fixClock() {
        clock.now = now
    }

    private fun body(
        event: String,
        order: Long,
        transaction: String,
        amount: String = "100",
        type: String = "PAYMENT_APPROVED",
        failureReason: String = "null",
    ) = """{"eventId":"$event","type":"$type","transactionId":"$transaction","orderId":"$order","amount":$amount,""" +
        """"occurredAt":"$now","failureReason":$failureReason}"""

    private fun sign(timestamp: String, raw: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TEST_WEBHOOK_SECRET.toByteArray(), "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal("$timestamp.$raw".toByteArray()))
    }

    private fun send(raw: String, time: String = now.epochSecond.toString(), signature: String = sign(time, raw)): MvcTestResult =
        mockMvcTester.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-FakePg-Timestamp", time)
            .header("X-FakePg-Signature", signature)
            .content(raw)
            .exchange()

    private fun payment(order: Long, transaction: String, prepare: Payment.() -> Unit = {}) {
        tx.execute {
            val requestedPayment = Payment.request(OrderId(order), MemberId(1), BigDecimal("100"), UUID.randomUUID().toString(), now)
            requestedPayment.attachPgTransaction(transaction)
            requestedPayment.prepare()
            payments.save(requestedPayment)
        }
    }

    @Test
    fun `정상 웹훅과 중복은 성공하고 계약 오류와 미등록 거래 기록은 롤백된다`() {
        val order = System.nanoTime()
        val transaction = UUID.randomUUID().toString()
        val event = UUID.randomUUID().toString()
        payment(order, transaction)
        val raw = body(event, order, transaction)

        send(raw).expectStatus(HttpStatus.OK)
        send(raw).expectStatus(HttpStatus.OK)
        assertThat(webhookCount(event)).isEqualTo(1)

        assertThat(
            send(
                raw.replace(
                    event,
                    UUID.randomUUID().toString(),
                ).replace("PAYMENT_APPROVED", "PAYMENT_FAILED").replace("\"failureReason\":null", "\"failureReason\":\"declined\""),
            ),
        )
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("INVALID_PAYMENT_STATE")

        val mismatch = UUID.randomUUID().toString()
        assertThat(send(body(mismatch, order, transaction, "101")))
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("PAYMENT_CONTRACT_MISMATCH")

        val missing = UUID.randomUUID().toString()
        assertThat(send(body(missing, order + 1, UUID.randomUUID().toString())))
            .hasStatus(HttpStatus.NOT_FOUND)
            .bodyJson().extractingPath("$.code").isEqualTo("PAYMENT_NOT_FOUND")

        assertThat(
            jdbc.queryForObject("select count(*) from payment.webhook_event where event_id in (?, ?)", Int::class.java, mismatch, missing),
        ).isZero()
    }

    private fun webhookCount(event: String) =
        jdbc.queryForObject("select count(*) from payment.webhook_event where event_id = ?", Int::class.java, event)

    @Test
    fun `동일하거나 다른 이벤트 ID로 승인 웹훅을 동시에 보내도 결제 결과는 한 건이다`() {
        val order = System.nanoTime()
        val transaction = UUID.randomUUID().toString()
        val event = UUID.randomUUID().toString()
        val distinct = UUID.randomUUID().toString()
        payment(order, transaction)
        val raw = body(event, order, transaction)
        val deliveries = listOf(raw, raw, body(distinct, order, transaction))
        val ready = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val requests = deliveries.map { payload ->
                executor.submit<Int> {
                    ready.await()
                    send(payload).response.status
                }
            }
            ready.countDown()
            requests.forEachIndexed { index, response ->
                val status = response.get(20, TimeUnit.SECONDS)
                assertThat(status).isIn(HttpStatus.OK.value(), HttpStatus.CONFLICT.value())
                if (status == HttpStatus.CONFLICT.value()) send(deliveries[index]).expectStatus(HttpStatus.OK)
            }
        }
        send(raw).expectStatus(HttpStatus.OK)

        assertThat(webhookCount(event)).isEqualTo(1)
        assertThat(webhookCount(distinct)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select status from payment.payment where order_id = ?", String::class.java, order))
            .isEqualTo("SUCCEEDED")
        awaitResult(topics.paymentSucceeded, order)
    }

    @Test
    fun `PG 응답 전에 서명된 승인 웹훅이 거래를 연결하고 지연된 응답에도 결제 상태를 유지한다`() {
        val order = System.nanoTime()
        val event = UUID.randomUUID().toString()
        val command = RequestPaymentCommand(order, 1, BigDecimal("100"), "late-$order", now)
        val gate = CountDownLatch(1)
        pg.gate = gate

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val response = executor.submit { requestPayment.request(command) }
            try {
                await().atMost(Duration.ofSeconds(10)).untilAsserted {
                    assertThat(pg.requests.map { it.orderId }).contains(order.toString())
                }

                send(body(event, order, "tx-$order")).expectStatus(HttpStatus.OK)
            } finally {
                gate.countDown()
            }
            response.get(15, TimeUnit.SECONDS)
        }

        assertThat(webhookCount(event)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select status from payment.payment where order_id = ?", String::class.java, order))
            .isEqualTo("SUCCEEDED")
        awaitResult(topics.paymentSucceeded, order)
    }

    @Test
    fun `서명된 결제 실패 웹훅을 재전송해도 실패 결과는 한 건이다`() {
        val order = System.nanoTime()
        val transaction = UUID.randomUUID().toString()
        val event = UUID.randomUUID().toString()
        payment(order, transaction)
        val raw = body(event, order, transaction, type = "PAYMENT_FAILED", failureReason = "\"declined\"")

        repeat(2) { send(raw).expectStatus(HttpStatus.OK) }

        assertThat(webhookCount(event)).isEqualTo(1)
        assertThat(
            jdbc.queryForObject("select status from payment.payment where order_id = ?", String::class.java, order),
        ).isEqualTo("FAILED")
        awaitResult(topics.paymentFailed, order)
    }

    @Test
    fun `서명된 환불 완료 웹훅을 재전송해도 환불 결과는 한 건이다`() {
        val order = System.nanoTime()
        val transaction = UUID.randomUUID().toString()
        val event = UUID.randomUUID().toString()
        payment(order, transaction) {
            approve(BigDecimal("100"), now)
            requestRefund(now)
        }
        val raw = body(event, order, transaction, type = "REFUND_COMPLETED")

        repeat(2) { send(raw).expectStatus(HttpStatus.OK) }

        assertThat(webhookCount(event)).isEqualTo(1)
        assertThat(
            jdbc.queryForObject("select status from payment.payment where order_id = ?", String::class.java, order),
        ).isEqualTo("REFUNDED")
        awaitResult(topics.paymentRefunded, order)
    }

    private fun awaitResult(topic: String, order: Long) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(KafkaTestSupport.recordsOf(kafka.bootstrapServers, topic, order.toString())).hasSize(1)
        }
    }

    @Test
    fun `서명은 원문과 시간 허용 경계를 검증하고 파싱보다 먼저 수행된다`() {
        val raw = "{bad"

        mockMvcTester.post().uri(path).contentType(MediaType.APPLICATION_JSON).content(raw)
            .assertThat()
            .hasStatus(HttpStatus.UNAUTHORIZED)
            .bodyJson().extractingPath("$.code").isEqualTo("INVALID_WEBHOOK_SIGNATURE")

        assertThat(send(raw, signature = "xx")).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(send(raw, signature = "00".repeat(32))).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(send(raw, time = "999999999999999999999999")).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(send(raw, time = (now.epochSecond - 301).toString())).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(send(raw, time = (now.epochSecond + 301).toString())).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(send(raw, time = (now.epochSecond - 300).toString())).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(send(raw, time = (now.epochSecond + 300).toString())).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(send(raw + " ", signature = sign(now.epochSecond.toString(), raw))).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(send(raw))
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson().extractingPath("$.code").isEqualTo("INVALID_PAYMENT_WEBHOOK")

        mockMvcTester.get().uri(path).assertThat().hasStatus(HttpStatus.UNAUTHORIZED)
        mockMvcTester.post().uri("$path/other").assertThat().hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `서명된 잘못된 필드와 타입은 입력 오류다`() {
        val valid = body("event", 1, "tx")
        val invalidBodies = listOf(
            valid + " {}",
            valid + " trailing",
            valid.replace("\"amount\":100", "\"amount\":1.2"),
            valid.replace("\"orderId\":\"1\"", "\"orderId\":1"),
            valid.replace("\"event\"", "\"${"x".repeat(65)}\""),
            valid.replace("PAYMENT_APPROVED", "UNKNOWN"),
            valid.replace("\"amount\":100", "\"amount\":0"),
            valid.replace("\"orderId\":\"1\"", "\"orderId\":\"0\""),
        )

        invalidBodies.forEach {
            assertThat(send(it))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_PAYMENT_WEBHOOK")
        }
    }
}
