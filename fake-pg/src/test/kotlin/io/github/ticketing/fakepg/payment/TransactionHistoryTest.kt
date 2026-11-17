package io.github.ticketing.fakepg.payment

import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransactionHistoryTest(mockMvcTester: MockMvcTester, clock: MutableClock) : FakePgTestSupport(mockMvcTester, clock) {

    @Test
    fun `거래 내역은 요청 시각의 한국 날짜로 필터링한다`() {
        clock.set(Instant.parse("2031-05-05T14:59:30Z"))
        val dayOne = transactionIdOf(requestPayment("order-day1", scenario = "DELAYED"))
        clock.set(Instant.parse("2031-05-05T15:00:30Z"))
        val dayTwo = transactionIdOf(requestPayment("order-day2", scenario = "DELAYED"))

        val first = listTransactions("2031-05-05")
        val second = listTransactions("2031-05-06")

        assertEquals(listOf(dayOne), first.map { it.get("transactionId").asString() })
        assertEquals(listOf(dayTwo), second.map { it.get("transactionId").asString() })
        val tx = first.single()
        assertEquals("order-day1", tx.get("orderId").asString())
        assertEquals(12000, tx.get("amount").asLong())
        assertEquals("PENDING", tx.get("status").asString())
        assertEquals("2031-05-05T14:59:30Z", tx.get("requestedAt").asString())
        assertTrue(tx.get("approvedAt").isNull)
        assertTrue(tx.get("refundedAt").isNull)
    }

    @Test
    fun `UTC 전날 자정 이후 거래는 한국 날짜로만 조회된다`() {
        clock.set(Instant.parse("2026-11-10T15:30:00Z"))
        val transactionId = transactionIdOf(requestPayment("order-kst-boundary", scenario = "DELAYED"))

        val followingDay = listTransactions("2026-11-11")
        val previousDay = listTransactions("2026-11-10")

        assertEquals(listOf(transactionId), followingDay.map { it.get("transactionId").asString() })
        assertTrue(previousDay.none { it.get("transactionId").asString() == transactionId })
    }

    @Test
    fun `거래가 없는 날짜는 빈 배열이다`() {
        assertEquals(0, listTransactions("1999-01-01").size)
    }

    @Test
    fun `date가 없거나 형식이 틀리면 400이다`() {
        mockMvcTester.get().uri("/v1/transactions").assertThat().hasStatus(HttpStatus.BAD_REQUEST)
        mockMvcTester.get().uri("/v1/transactions").param("date", "2031/05/05").assertThat().hasStatus(HttpStatus.BAD_REQUEST)
    }
}
