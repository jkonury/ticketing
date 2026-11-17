package io.github.ticketing.fakepg.payment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals

class PaymentIdempotencyTest(mockMvcTester: MockMvcTester, clock: MutableClock) : FakePgTestSupport(mockMvcTester, clock) {

    @Test
    fun `같은 Idempotency-Key로 다시 요청하면 같은 transactionId를 돌려주고 웹훅은 한 번만 나간다`() {
        val firstResult = requestPayment("order-idem", key = "idem-1")
        assertThat(firstResult).hasStatus(HttpStatus.ACCEPTED)
        val secondResult = requestPayment("order-idem", key = "idem-1")
        assertThat(secondResult).hasStatus(HttpStatus.ACCEPTED)

        assertEquals(transactionIdOf(firstResult), transactionIdOf(secondResult))
        awaitWebhooks("order-idem", 1)
        assertNoMoreWebhooks("order-idem", 1)
        assertEquals(1, listTransactions("2030-01-01").count { it.get("orderId").asString() == "order-idem" })
    }

    @Test
    fun `같은 키에 본문이 다르면 422 IDEMPOTENCY_KEY_REUSED이다`() {
        assertThat(requestPayment("order-reuse", key = "idem-2", amount = 12000)).hasStatus(HttpStatus.ACCEPTED)

        assertThat(requestPayment("order-reuse", key = "idem-2", amount = 13000))
            .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
            .bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(requestPayment("order-reuse-other", key = "idem-2", amount = 12000))
            .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    }

    @Test
    fun `동시에 같은 키로 요청해도 거래는 하나만 만들어진다`() {
        val results = ConcurrentLinkedQueue<String>()
        val threads = (1..8).map {
            Thread.ofVirtual().start { results.add(transactionIdOf(requestPayment("order-race", key = "idem-race"))) }
        }
        threads.forEach { it.join() }

        assertEquals(8, results.size)
        assertEquals(1, results.toSet().size)
        awaitWebhooks("order-race", 1)
        assertNoMoreWebhooks("order-race", 1)
    }

    @Test
    fun `Idempotency-Key 헤더가 없으면 400이다`() {
        mockMvcTester.post()
            .uri("/v1/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"orderId":"o","amount":1}""")
            .assertThat()
            .hasStatus(HttpStatus.BAD_REQUEST)
    }
}
