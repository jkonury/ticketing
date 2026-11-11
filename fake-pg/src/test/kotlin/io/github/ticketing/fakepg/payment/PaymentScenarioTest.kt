package io.github.ticketing.fakepg.payment

import io.github.ticketing.fakepg.payment.service.WebhookSender
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PaymentScenarioTest(mockMvc: MockMvc, clock: MutableClock) : FakePgTestSupport(mockMvc, clock) {

    @Test
    fun `SUCCESS는 202 PENDING을 돌려주고 승인 웹훅 1건을 보낸다`() {
        val result = requestPayment("order-success", scenario = "SUCCESS")
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.transactionId").isNotEmpty)
        val transactionId = transactionIdOf(result)

        val webhooks = awaitWebhooks("order-success", 1)
        assertNoMoreWebhooks("order-success", 1)

        val event = webhooks.single().json
        assertEquals("PAYMENT_APPROVED", event.get("type").asString())
        assertEquals(transactionId, event.get("transactionId").asString())
        assertEquals(12000, event.get("amount").asLong())
        assertTrue(event.get("failureReason").isNull)
        assertNotNull(Instant.parse(event.get("occurredAt").asString()))
        await.atMost(Duration.ofSeconds(3)).untilAsserted { assertEquals("APPROVED", statusOf(transactionId)) }
    }

    @Test
    fun `FAIL은 CARD_DECLINED 사유의 실패 웹훅을 보내고 거래는 FAILED가 된다`() {
        val transactionId = transactionIdOf(requestPayment("order-fail", scenario = "FAIL").andExpect(status().isAccepted))

        val event = awaitWebhooks("order-fail", 1).single().json

        assertEquals("PAYMENT_FAILED", event.get("type").asString())
        assertEquals("CARD_DECLINED", event.get("failureReason").asString())
        await.atMost(Duration.ofSeconds(3)).untilAsserted { assertEquals("FAILED", statusOf(transactionId)) }
    }

    @Test
    fun `TIMEOUT은 응답만 timeout-delay만큼 늦고 결제는 정상 승인된다`() {
        val started = System.nanoTime()
        val result = requestPayment("order-timeout", scenario = "TIMEOUT").andExpect(status().isAccepted)
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertTrue(elapsed >= Duration.ofMillis(500), "응답이 timeout-delay(500ms)만큼 늦어야 한다: $elapsed")
        val event = awaitWebhooks("order-timeout", 1).single().json
        assertEquals("PAYMENT_APPROVED", event.get("type").asString())
        assertEquals(transactionIdOf(result), event.get("transactionId").asString())
    }

    @Test
    fun `DELAYED는 delayed-webhook-delay 동안 PENDING으로 남다가 승인 웹훅을 보낸다`() {
        val transactionId = transactionIdOf(requestPayment("order-delayed", scenario = "DELAYED").andExpect(status().isAccepted))

        assertEquals("PENDING", statusOf(transactionId))
        assertTrue(TestWebhookReceiver.forOrder("order-delayed").isEmpty())

        val event = awaitWebhooks("order-delayed", 1).single().json
        assertEquals("PAYMENT_APPROVED", event.get("type").asString())
        assertEquals("APPROVED", statusOf(transactionId))
    }

    @Test
    fun `DUPLICATE_WEBHOOK은 같은 eventId의 승인 웹훅을 duplicate-count만큼 더 보낸다`() {
        requestPayment("order-dup", scenario = "DUPLICATE_WEBHOOK").andExpect(status().isAccepted)

        val webhooks = awaitWebhooks("order-dup", 3)
        assertNoMoreWebhooks("order-dup", 3)

        assertEquals(1, webhooks.map { it.eventId }.toSet().size, "모두 같은 eventId여야 한다")
        assertTrue(webhooks.all { it.type == "PAYMENT_APPROVED" })
        assertEquals(1, webhooks.map { it.rawBody }.toSet().size, "본문도 같아야 한다")
    }

    @Test
    fun `웹훅 서명은 timestamp와 원문 본문으로 계산한 HMAC-SHA256 hex와 같다`() {
        requestPayment("order-sign", scenario = "SUCCESS")

        val webhook = awaitWebhooks("order-sign", 1).single()

        assertNotNull(webhook.timestamp)
        assertEquals(clock.instant().epochSecond.toString(), webhook.timestamp)
        assertEquals(expectedSignature(webhook), webhook.signature)
        assertEquals(
            WebhookSender.sign("test-webhook-secret", webhook.timestamp.orEmpty(), webhook.rawBody),
            webhook.signature,
        )
    }

    @Test
    fun `시나리오를 생략하면 확률이 0일 때 SUCCESS로 진행한다`() {
        requestPayment("order-default").andExpect(status().isAccepted)

        assertEquals("PAYMENT_APPROVED", awaitWebhooks("order-default", 1).single().type)
    }

    @Test
    fun `알 수 없는 시나리오나 잘못된 금액은 400이다`() {
        requestPayment("order-bad", scenario = "UNKNOWN").andExpect(status().isBadRequest)
        requestPayment("order-bad", key = "k-neg", amount = -1).andExpect(status().isBadRequest)
    }
}
