package io.github.ticketing.fakepg.payment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import kotlin.test.assertEquals

/** 기본 설정(`fake-pg.test-controls.enabled=false`)에서는 승인 보류 제어가 없고 결제는 그대로 승인된다. */
class ApprovalHoldDisabledTest(mockMvcTester: MockMvcTester, clock: MutableClock) : FakePgTestSupport(mockMvcTester, clock) {

    @Test
    fun `기본 설정에서는 승인 보류 제어 경로가 닫혀 있다`() {
        assertThat(mockMvcTester.post().uri("/v1/test/approval-holds/order-closed").exchange()).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(mockMvcTester.get().uri("/v1/test/approval-holds/order-closed").exchange()).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(mockMvcTester.post().uri("/v1/test/approval-holds/order-closed/release").exchange()).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(mockMvcTester.post().uri("/v1/test/approval-holds/order-closed/fail").exchange()).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `기본 설정에서는 등록을 시도한 주문도 일반 결제처럼 승인된다`() {
        mockMvcTester.post().uri("/v1/test/approval-holds/order-closed-pay").exchange()

        requestPayment("order-closed-pay", scenario = "SUCCESS")

        assertEquals("PAYMENT_APPROVED", awaitWebhooks("order-closed-pay", 1).single().type)
    }
}
