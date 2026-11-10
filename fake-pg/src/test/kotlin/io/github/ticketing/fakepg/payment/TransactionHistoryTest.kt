package io.github.ticketing.fakepg.payment

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransactionHistoryTest : FakePgTestSupport() {

    @Test
    fun `거래 내역은 요청 시각의 UTC 날짜로 필터링한다`() {
        clock.set(Instant.parse("2031-05-05T23:59:30Z"))
        val dayOne = transactionIdOf(requestPayment("order-day1", scenario = "DELAYED"))
        clock.set(Instant.parse("2031-05-06T00:00:30Z"))
        val dayTwo = transactionIdOf(requestPayment("order-day2", scenario = "DELAYED"))

        val first = listTransactions("2031-05-05")
        val second = listTransactions("2031-05-06")

        assertEquals(listOf(dayOne), first.map { it.get("transactionId").asString() })
        assertEquals(listOf(dayTwo), second.map { it.get("transactionId").asString() })
        val tx = first.single()
        assertEquals("order-day1", tx.get("orderId").asString())
        assertEquals(12000, tx.get("amount").asLong())
        assertEquals("PENDING", tx.get("status").asString())
        assertEquals("2031-05-05T23:59:30Z", tx.get("requestedAt").asString())
        assertTrue(tx.get("approvedAt").isNull)
        assertTrue(tx.get("refundedAt").isNull)
    }

    @Test
    fun `거래가 없는 날짜는 빈 배열이다`() {
        assertEquals(0, listTransactions("1999-01-01").size)
    }

    @Test
    fun `date가 없거나 형식이 틀리면 400이다`() {
        mockMvc.perform(get("/v1/transactions")).andExpect(status().isBadRequest)
        mockMvc.perform(get("/v1/transactions").param("date", "2031/05/05")).andExpect(status().isBadRequest)
    }
}
