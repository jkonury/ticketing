package io.github.ticketing.fakepg.payment

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import kotlin.test.assertEquals

class WebhookRetryTest(mockMvc: MockMvc, clock: MutableClock) : FakePgTestSupport(mockMvc, clock) {

    @Test
    fun `수신 측이 2xx가 아니면 성공할 때까지 같은 이벤트를 재시도한다`() {
        TestWebhookReceiver.failNext("order-retry", 2)

        requestPayment("order-retry", scenario = "SUCCESS")

        val webhooks = awaitWebhooks("order-retry", 3)
        assertNoMoreWebhooks("order-retry", 3)
        assertEquals(listOf(500, 500, 200), webhooks.map { it.statusReturned })
        assertEquals(1, webhooks.map { it.eventId }.toSet().size, "재시도도 같은 eventId여야 한다")
        assertEquals(1, webhooks.map { it.rawBody }.toSet().size)
    }

    @Test
    fun `계속 실패하면 max-attempts에서 멈춘다`() {
        TestWebhookReceiver.failNext("order-retry-giveup", 100)

        requestPayment("order-retry-giveup", scenario = "SUCCESS")

        awaitWebhooks("order-retry-giveup", 3)
        assertNoMoreWebhooks("order-retry-giveup", 3)
    }
}
