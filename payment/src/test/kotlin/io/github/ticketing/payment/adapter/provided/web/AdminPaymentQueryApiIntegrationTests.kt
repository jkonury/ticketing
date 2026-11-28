package io.github.ticketing.payment.adapter.provided.web

import io.github.ticketing.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Instant
import java.time.ZoneOffset
import ticketing.jooq.payment.tables.references.PAYMENT_ as PAYMENT

class AdminPaymentQueryApiIntegrationTests(private val mockMvcTester: MockMvcTester, private val dsl: DSLContext) :
    IntegrationTestSupport() {
    @Test
    fun `관리자는 다른 회원의 결제와 환불을 예매 ID로 조회한다`() {
        val bookingId = System.nanoTime()
        val requested = Instant.parse("2030-01-01T00:00:00Z").atOffset(ZoneOffset.UTC)
        dsl.insertInto(PAYMENT)
            .set(PAYMENT.ORDER_ID, bookingId).set(PAYMENT.MEMBER_ID, 10L).set(PAYMENT.AMOUNT, 13000L)
            .set(PAYMENT.STATUS, "REFUNDED").set(PAYMENT.IDEMPOTENCY_KEY, "admin-query-$bookingId")
            .set(PAYMENT.REQUESTED_AT, requested).set(PAYMENT.APPROVED_AT, requested.plusMinutes(1))
            .set(PAYMENT.REFUND_REQUESTED_AT, requested.plusMinutes(2)).set(PAYMENT.REFUNDED_AT, requested.plusMinutes(3))
            .execute()

        assertThat(request(bookingId, "ADMIN")).hasStatus(HttpStatus.OK).bodyJson().apply {
            extractingPath("$.length()").isEqualTo(1)
            extractingPath("$[0].bookingId").convertTo(Long::class.java).isEqualTo(bookingId)
            extractingPath("$[0].status").isEqualTo("REFUNDED")
            extractingPath("$[0].amount").isEqualTo(13000)
            extractingPath("$[0].refundRequestedAt").isEqualTo("2030-01-01T00:02:00Z")
            extractingPath("$[0].refundedAt").isEqualTo("2030-01-01T00:03:00Z")
        }
    }

    @Test
    fun `일반 회원과 주최자는 관리자 결제를 조회할 수 없다`() {
        for (role in listOf("USER", "ORGANIZER")) {
            assertThat(request(1, role)).hasStatus(HttpStatus.FORBIDDEN)
        }
    }

    @Test
    fun `결제가 없는 예매는 빈 배열을 반환한다`() {
        assertThat(request(Long.MAX_VALUE, "ADMIN")).hasStatus(HttpStatus.OK).bodyJson().isEqualTo("[]")
    }

    private fun request(bookingId: Long, role: String) = mockMvcTester.get().uri("/api/v1/admin/payments")
        .param("bookingId", "$bookingId")
        .header("Authorization", "Bearer ${PaymentQueryFixture.token(1, role)}")
}
