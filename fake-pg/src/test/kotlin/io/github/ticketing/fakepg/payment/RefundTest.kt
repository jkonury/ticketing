package io.github.ticketing.fakepg.payment

import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import kotlin.test.assertEquals

class RefundTest : FakePgTestSupport() {

    private fun approvedTransaction(orderId: String): String {
        val id = transactionIdOf(requestPayment(orderId, scenario = "SUCCESS").andExpect(status().isAccepted))
        awaitWebhooks(orderId, 1)
        await.atMost(Duration.ofSeconds(3)).untilAsserted { assertEquals("APPROVED", statusOf(id)) }
        return id
    }

    private fun refund(transactionId: String, key: String) =
        mockMvc.perform(post("/v1/payments/$transactionId/refund").header("Idempotency-Key", key))

    @Test
    fun `승인된 거래를 환불하면 202이고 REFUND_COMPLETED 웹훅을 보낸다`() {
        val id = approvedTransaction("order-refund")

        refund(id, "refund-1").andExpect(status().isAccepted)

        val webhooks = awaitWebhooks("order-refund", 2)
        assertEquals(setOf("PAYMENT_APPROVED", "REFUND_COMPLETED"), webhooks.map { it.type }.toSet())
        assertEquals("REFUNDED", statusOf(id))
        val tx = listTransactions("2030-01-01").first { it.get("transactionId").asString() == id }
        assertEquals(false, tx.get("refundedAt").isNull)
    }

    @Test
    fun `같은 키로 환불을 다시 요청해도 웹훅은 한 번만 나간다`() {
        val id = approvedTransaction("order-refund-idem")

        refund(id, "refund-2").andExpect(status().isAccepted)
        refund(id, "refund-2").andExpect(status().isAccepted)

        awaitWebhooks("order-refund-idem", 2)
        assertNoMoreWebhooks("order-refund-idem", 2)
    }

    @Test
    fun `이미 환불된 거래를 다른 키로 환불하면 409이다`() {
        val id = approvedTransaction("order-refund-twice")
        refund(id, "refund-3a").andExpect(status().isAccepted)

        refund(id, "refund-3b")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_REFUNDABLE"))
    }

    @Test
    fun `승인되지 않은 거래는 환불할 수 없어 409이다`() {
        val id = transactionIdOf(requestPayment("order-refund-pending", scenario = "DELAYED"))
        val failed = transactionIdOf(requestPayment("order-refund-failed", scenario = "FAIL"))
        awaitWebhooks("order-refund-failed", 1)

        refund(id, "refund-4a").andExpect(status().isConflict)
        await.atMost(Duration.ofSeconds(3)).untilAsserted { assertEquals("FAILED", statusOf(failed)) }
        refund(failed, "refund-4b").andExpect(status().isConflict)
    }

    @Test
    fun `없는 거래를 환불하면 404이다`() {
        refund("txn_missing", "refund-5").andExpect(status().isNotFound)
    }
}
