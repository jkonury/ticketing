package io.github.ticketing.fakepg.payment

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.time.Duration
import kotlin.test.assertEquals

class RefundTest(mockMvcTester: MockMvcTester, clock: MutableClock) : FakePgTestSupport(mockMvcTester, clock) {

    private fun approvedTransaction(orderId: String): String {
        val result = requestPayment(orderId, scenario = "SUCCESS")
        assertThat(result).hasStatus(HttpStatus.ACCEPTED)
        val id = transactionIdOf(result)
        awaitWebhooks(orderId, 1)
        await.atMost(Duration.ofSeconds(3)).untilAsserted { assertEquals("APPROVED", statusOf(id)) }
        return id
    }

    private fun refund(transactionId: String, key: String): MvcTestResult =
        mockMvcTester.post().uri("/v1/payments/$transactionId/refund").header("Idempotency-Key", key).exchange()

    @Test
    fun `승인된 거래를 환불하면 202이고 REFUND_COMPLETED 웹훅을 보낸다`() {
        val id = approvedTransaction("order-refund")

        assertThat(refund(id, "refund-1")).hasStatus(HttpStatus.ACCEPTED)

        val webhooks = awaitWebhooks("order-refund", 2)
        assertEquals(setOf("PAYMENT_APPROVED", "REFUND_COMPLETED"), webhooks.map { it.type }.toSet())
        assertEquals("REFUNDED", statusOf(id))
        val tx = listTransactions("2030-01-01").first { it.get("transactionId").asString() == id }
        assertEquals(false, tx.get("refundedAt").isNull)
    }

    @Test
    fun `같은 키로 환불을 다시 요청해도 웹훅은 한 번만 나간다`() {
        val id = approvedTransaction("order-refund-idem")

        assertThat(refund(id, "refund-2")).hasStatus(HttpStatus.ACCEPTED)
        assertThat(refund(id, "refund-2")).hasStatus(HttpStatus.ACCEPTED)

        awaitWebhooks("order-refund-idem", 2)
        assertNoMoreWebhooks("order-refund-idem", 2)
    }

    @Test
    fun `이미 환불된 거래를 다른 키로 환불하면 409이다`() {
        val id = approvedTransaction("order-refund-twice")
        assertThat(refund(id, "refund-3a")).hasStatus(HttpStatus.ACCEPTED)

        assertThat(refund(id, "refund-3b"))
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("TRANSACTION_NOT_REFUNDABLE")
    }

    @Test
    fun `승인되지 않은 거래는 환불할 수 없어 409이다`() {
        val id = transactionIdOf(requestPayment("order-refund-pending", scenario = "DELAYED"))
        val failed = transactionIdOf(requestPayment("order-refund-failed", scenario = "FAIL"))
        awaitWebhooks("order-refund-failed", 1)

        assertThat(refund(id, "refund-4a")).hasStatus(HttpStatus.CONFLICT)
        await.atMost(Duration.ofSeconds(3)).untilAsserted { assertEquals("FAILED", statusOf(failed)) }
        assertThat(refund(failed, "refund-4b")).hasStatus(HttpStatus.CONFLICT)
    }

    @Test
    fun `없는 거래를 환불하면 404이다`() {
        assertThat(refund("txn_missing", "refund-5")).hasStatus(HttpStatus.NOT_FOUND)
    }
}
