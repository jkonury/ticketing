package io.github.ticketing.payment.application.flow

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.TEST_WEBHOOK_SECRET
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import io.github.ticketing.payment.PaymentFailed
import io.github.ticketing.payment.PaymentRefunded
import io.github.ticketing.payment.PaymentRequested
import io.github.ticketing.payment.PaymentSucceeded
import io.github.ticketing.payment.RefundRequested
import io.github.ticketing.payment.application.port.required.PaymentCommandPort
import io.github.ticketing.payment.application.port.required.PgPaymentRequest
import io.github.ticketing.payment.application.port.required.PgPort
import io.github.ticketing.payment.application.port.required.PgRequestResult
import io.github.ticketing.payment.domain.OrderId
import io.github.ticketing.payment.domain.Payment
import io.github.ticketing.payment.domain.PaymentStatus
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@AutoConfigureMockMvc
@Import(PaymentPgFlowTests.FakePgConfig::class)
class PaymentPgFlowTests : IntegrationTestSupport() {
    /** 호출을 기록하고 결과를 테스트가 정하는 PG. 기본은 `tx-{orderId}`로 접수한다. */
    class RecordingPgPort : PgPort {
        val paymentCalls = CopyOnWriteArrayList<PgPaymentRequest>()
        val refundCalls = CopyOnWriteArrayList<Pair<String, String>>()

        @Volatile var paymentResult: (PgPaymentRequest) -> PgRequestResult = { PgRequestResult.Accepted(transactionIdOf(it.orderId)) }

        @Volatile var refundResult: (String) -> PgRequestResult = { PgRequestResult.Accepted(it) }

        override fun requestPayment(request: PgPaymentRequest): PgRequestResult {
            paymentCalls += request
            return paymentResult(request)
        }

        override fun requestRefund(transactionId: String, idempotencyKey: String): PgRequestResult {
            refundCalls += transactionId to idempotencyKey
            return refundResult(transactionId)
        }

        fun reset() {
            paymentResult = { PgRequestResult.Accepted(transactionIdOf(it.orderId)) }
            refundResult = { PgRequestResult.Accepted(it) }
        }

        companion object {
            fun transactionIdOf(orderId: Any) = "tx-$orderId"
        }
    }

    class PaymentEventCollector {
        val events = CopyOnWriteArrayList<Any>()

        @EventListener
        fun on(event: Any) {
            if (event is PaymentSucceeded || event is PaymentFailed || event is PaymentRefunded) events += event
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FakePgConfig {
        @Bean
        @Primary
        fun recordingPgPort() = RecordingPgPort()

        @Bean
        fun paymentEventCollector() = PaymentEventCollector()
    }

    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var pg: RecordingPgPort

    @Autowired lateinit var collector: PaymentEventCollector

    @Autowired lateinit var publisher: ApplicationEventPublisher

    @Autowired lateinit var tx: TransactionTemplate

    @Autowired lateinit var payments: PaymentCommandPort

    @Autowired lateinit var register: RegisterScheduleSeatsUseCase

    private val json = JsonMapper.builder().build()
    private val ids = AtomicLong(System.nanoTime() % 1_000_000_000L * 100)
    private val amount = BigDecimal(30_000)

    @BeforeEach
    fun resetPg() {
        pg.reset()
    }

    private fun publish(event: Any) = tx.executeWithoutResult { publisher.publishEvent(event) }

    private fun requestPayment(orderId: Long, key: String = "booking-$orderId") =
        publish(PaymentRequested(orderId, 7, amount, key, Instant.now()))

    private fun paymentOf(orderId: Long): Payment? = tx.execute { payments.findByOrderId(OrderId(orderId)) }

    private fun paymentCallsOf(orderId: Long) = pg.paymentCalls.filter { it.orderId == orderId.toString() }

    private fun refundCallsOf(orderId: Long) = pg.refundCalls.filter { it.second == "refund-$orderId" }

    private inline fun <reified T : Any> eventsOf(orderId: Long): List<T> = collector.events.filterIsInstance<T>().filter {
        when (it) {
            is PaymentSucceeded -> it.orderId == orderId
            is PaymentFailed -> it.orderId == orderId
            is PaymentRefunded -> it.orderId == orderId
            else -> false
        }
    }

    private fun awaitUntil(assertion: () -> Unit) = await().atMost(Duration.ofSeconds(10)).untilAsserted(assertion)

    private fun awaitAttached(orderId: Long) = awaitUntil {
        assertThat(paymentOf(orderId)?.pgTransactionId).isEqualTo(RecordingPgPort.transactionIdOf(orderId))
    }

    private fun webhookBody(
        type: String,
        orderId: Long,
        eventId: String = UUID.randomUUID().toString(),
        transactionId: String = RecordingPgPort.transactionIdOf(orderId),
        amount: BigDecimal = this.amount,
        failureReason: String? = null,
    ): String = json.writeValueAsString(
        mapOf(
            "eventId" to eventId, "type" to type, "transactionId" to transactionId, "orderId" to orderId.toString(),
            "amount" to amount, "occurredAt" to Instant.now().toString(), "failureReason" to failureReason,
        ),
    )

    private fun sign(timestamp: String, body: ByteArray, secret: String = TEST_WEBHOOK_SECRET): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        mac.update("$timestamp.".toByteArray(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(mac.doFinal(body))
    }

    private fun sendWebhook(
        body: String,
        sentAt: Instant = Instant.now(),
        signature: String? = null,
    ): ResultActions {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val timestamp = sentAt.epochSecond.toString()
        return mvc.perform(
            post("/api/v1/payments/webhooks/fake-pg").contentType(MediaType.APPLICATION_JSON).content(bytes)
                .header("X-FakePg-Timestamp", timestamp)
                .header("X-FakePg-Signature", signature ?: sign(timestamp, bytes)),
        )
    }

    @Test
    fun `PG가 접수한 결제에 승인 웹훅이 3번 와도 PaymentSucceeded는 1건이다`() {
        val orderId = ids.incrementAndGet()
        requestPayment(orderId)
        awaitAttached(orderId)

        val call = paymentCallsOf(orderId).single()
        assertThat(call.idempotencyKey).isEqualTo("booking-$orderId")
        assertThat(call.amount).isEqualByComparingTo(amount)

        val body = webhookBody("PAYMENT_APPROVED", orderId, eventId = "evt-$orderId")
        repeat(3) { sendWebhook(body).andExpect(status().isOk) }

        assertThat(eventsOf<PaymentSucceeded>(orderId)).hasSize(1)
        assertThat(paymentOf(orderId)?.status).isEqualTo(PaymentStatus.SUCCEEDED)
    }

    @Test
    fun `같은 거래의 승인 웹훅이 다른 eventId로 다시 와도 이벤트를 다시 발행하지 않는다`() {
        val orderId = ids.incrementAndGet()
        requestPayment(orderId)
        awaitAttached(orderId)

        sendWebhook(webhookBody("PAYMENT_APPROVED", orderId)).andExpect(status().isOk)
        sendWebhook(webhookBody("PAYMENT_APPROVED", orderId)).andExpect(status().isOk)

        assertThat(eventsOf<PaymentSucceeded>(orderId)).hasSize(1)
    }

    @Test
    fun `PG가 거절하면 FAILED가 되고 PaymentFailed가 발행된다`() {
        val orderId = ids.incrementAndGet()
        pg.paymentResult = { PgRequestResult.Rejected("CARD_DECLINED") }
        requestPayment(orderId)

        awaitUntil { assertThat(eventsOf<PaymentFailed>(orderId)).hasSize(1) }
        assertThat(eventsOf<PaymentFailed>(orderId).single().reason).isEqualTo("CARD_DECLINED")
        assertThat(paymentOf(orderId)?.status).isEqualTo(PaymentStatus.FAILED)
    }

    @Test
    fun `PG 결과를 모르면 REQUESTED로 두고 이후 승인 웹훅이 오면 성공 처리한다`() {
        val orderId = ids.incrementAndGet()
        pg.paymentResult = { PgRequestResult.Unknown("HttpTimeoutException") }
        requestPayment(orderId)

        awaitUntil { assertThat(paymentCallsOf(orderId)).hasSize(1) }
        // 리스너가 결과를 반영할 시간을 준 뒤에도 실패로 확정되지 않아야 한다
        Thread.sleep(300)
        val pending = paymentOf(orderId)!!
        assertThat(pending.status).isEqualTo(PaymentStatus.REQUESTED)
        assertThat(pending.pgTransactionId).isNull()
        assertThat(eventsOf<PaymentFailed>(orderId)).isEmpty()

        // 거래 ID가 아직 없으므로 orderId로 찾고 거래 ID도 붙인다
        sendWebhook(webhookBody("PAYMENT_APPROVED", orderId)).andExpect(status().isOk)

        val approved = paymentOf(orderId)!!
        assertThat(approved.status).isEqualTo(PaymentStatus.SUCCEEDED)
        assertThat(approved.pgTransactionId).isEqualTo(RecordingPgPort.transactionIdOf(orderId))
        assertThat(eventsOf<PaymentSucceeded>(orderId)).hasSize(1)
    }

    @Test
    fun `같은 PaymentRequested가 다시 와도 PG 호출은 1번이다`() {
        val orderId = ids.incrementAndGet()
        requestPayment(orderId)
        awaitAttached(orderId)

        requestPayment(orderId)
        Thread.sleep(500)

        assertThat(paymentCallsOf(orderId)).hasSize(1)
    }

    @Test
    fun `서명이 틀리거나 없거나 시각이 허용 오차를 벗어나면 401이다`() {
        val orderId = ids.incrementAndGet()
        requestPayment(orderId)
        awaitAttached(orderId)
        val body = webhookBody("PAYMENT_APPROVED", orderId)
        val bytes = body.toByteArray(StandardCharsets.UTF_8)

        val timestamp = Instant.now().epochSecond.toString()
        sendWebhook(body, signature = sign(timestamp, bytes, secret = "wrong-secret"))
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_SIGNATURE"))
        // 서명한 본문과 보낸 본문이 다르면 거부한다
        sendWebhook(body.replace("30000", "1"), signature = sign(timestamp, bytes))
            .andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/payments/webhooks/fake-pg").contentType(MediaType.APPLICATION_JSON).content(bytes))
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_SIGNATURE"))
        sendWebhook(body, sentAt = Instant.now().minus(Duration.ofMinutes(6)))
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_SIGNATURE"))
        sendWebhook(body, sentAt = Instant.now().plus(Duration.ofMinutes(6))).andExpect(status().isUnauthorized)

        assertThat(paymentOf(orderId)?.status).isEqualTo(PaymentStatus.REQUESTED)
        assertThat(eventsOf<PaymentSucceeded>(orderId)).isEmpty()
    }

    @Test
    fun `알 수 없는 거래의 웹훅은 404로 응답하고 기록하지 않아 재시도 때 반영된다`() {
        val orderId = ids.incrementAndGet()
        val body = webhookBody("PAYMENT_APPROVED", orderId, eventId = "evt-$orderId")

        sendWebhook(body).andExpect(status().isNotFound)

        requestPayment(orderId)
        awaitAttached(orderId)
        sendWebhook(body).andExpect(status().isOk)
        assertThat(eventsOf<PaymentSucceeded>(orderId)).hasSize(1)
    }

    @Test
    fun `RefundRequested가 오면 PG 환불을 1번 요청하고 환불 웹훅이 오면 PaymentRefunded를 발행한다`() {
        val orderId = ids.incrementAndGet()
        requestPayment(orderId)
        awaitAttached(orderId)
        sendWebhook(webhookBody("PAYMENT_APPROVED", orderId)).andExpect(status().isOk)

        publish(RefundRequested(orderId, "USER_REQUESTED", Instant.now()))
        awaitUntil { assertThat(paymentOf(orderId)?.status).isEqualTo(PaymentStatus.REFUND_REQUESTED) }
        assertThat(refundCallsOf(orderId)).containsExactly(RecordingPgPort.transactionIdOf(orderId) to "refund-$orderId")

        publish(RefundRequested(orderId, "USER_REQUESTED", Instant.now()))
        Thread.sleep(500)
        assertThat(refundCallsOf(orderId)).hasSize(1)

        val body = webhookBody("REFUND_COMPLETED", orderId)
        repeat(2) { sendWebhook(body).andExpect(status().isOk) }

        assertThat(eventsOf<PaymentRefunded>(orderId)).hasSize(1)
        assertThat(paymentOf(orderId)?.status).isEqualTo(PaymentStatus.REFUNDED)
    }

    @Test
    fun `PG 환불 결과를 모르면 상태를 바꾸지 않아 다시 처리할 때 같은 멱등 키로 재요청한다`() {
        val orderId = ids.incrementAndGet()
        requestPayment(orderId)
        awaitAttached(orderId)
        sendWebhook(webhookBody("PAYMENT_APPROVED", orderId)).andExpect(status().isOk)

        pg.refundResult = { PgRequestResult.Unknown("HTTP 503") }
        publish(RefundRequested(orderId, "USER_REQUESTED", Instant.now()))
        awaitUntil { assertThat(refundCallsOf(orderId)).hasSize(1) }
        Thread.sleep(300)
        assertThat(paymentOf(orderId)?.status).isEqualTo(PaymentStatus.SUCCEEDED)

        pg.refundResult = { PgRequestResult.Accepted(it) }
        publish(RefundRequested(orderId, "USER_REQUESTED", Instant.now()))
        awaitUntil { assertThat(paymentOf(orderId)?.status).isEqualTo(PaymentStatus.REFUND_REQUESTED) }
        assertThat(refundCallsOf(orderId)).hasSize(2).allMatch { it.second == "refund-$orderId" }
    }

    @Test
    fun `예매의 결제 요청이 PG를 거쳐 승인 웹훅을 받으면 예매가 CONFIRMED가 된다`() {
        val scheduleId = System.nanoTime()
        register.register(
            RegisterScheduleSeatsCommand(
                scheduleId, 1, "결제 연동 공연", 2, Instant.parse("2027-01-01T10:00:00Z"),
                listOf(RegisterScheduleSeatsCommand.SeatInfo(1, "R", "A구역 1번", BigDecimal(100_000))),
            ),
        )
        val token = token()
        val bookingId = book(token, scheduleId, listOf(1))

        mvc.perform(
            post("/api/v1/bookings/$bookingId/payments").header("Authorization", "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
        ).andExpect(status().isAccepted)
        awaitAttached(bookingId)
        assertThat(paymentCallsOf(bookingId).single().amount).isEqualByComparingTo("100000")

        sendWebhook(webhookBody("PAYMENT_APPROVED", bookingId, amount = BigDecimal(100_000))).andExpect(status().isOk)

        awaitUntil {
            val res = mvc.perform(get("/api/v1/bookings/$bookingId").header("Authorization", "Bearer $token"))
                .andExpect(status().isOk).andReturn().response.contentAsString
            assertThat(json.readValue(res, Map::class.java)["status"]).isEqualTo("CONFIRMED")
        }
    }

    private fun token(): String {
        val email = "pay-${UUID.randomUUID()}@example.com"
        mvc.perform(
            post("/api/v1/members").contentType(MediaType.APPLICATION_JSON).content(
                json.writeValueAsString(mapOf("email" to email, "password" to "password123", "name" to "홍길동", "role" to "USER")),
            ),
        ).andExpect(status().isCreated)
        val res = mvc.perform(
            post("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123"))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return json.readValue(res, Map::class.java)["accessToken"] as String
    }

    private fun book(token: String, scheduleId: Long, seatIds: List<Long>): Long {
        val res = mvc.perform(
            post("/api/v1/bookings").header("Authorization", "Bearer $token").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("scheduleId" to scheduleId, "seatIds" to seatIds))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return (json.readValue(res, Map::class.java)["bookingId"] as Number).toLong()
    }
}
