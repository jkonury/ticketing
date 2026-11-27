package io.github.ticketing.fakepg.payment

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Duration
import kotlin.test.assertEquals

@TestPropertySource(properties = ["fake-pg.test-controls.enabled=true"])
class ApprovalHoldTest(mockMvcTester: MockMvcTester, clock: MutableClock) : FakePgTestSupport(mockMvcTester, clock) {

    @Test
    fun `보류한 주문은 해제하기 전에는 승인 웹훅을 보내지 않고 해제하면 보낸다`() {
        assertThat(mockMvcTester.post().uri("/v1/test/approval-holds/order-hold").exchange()).hasStatus(HttpStatus.CREATED)
        val transactionId = transactionIdOf(requestPayment("order-hold", scenario = "SUCCESS"))

        await.atMost(Duration.ofSeconds(3)).untilAsserted {
            assertThat(mockMvcTester.get().uri("/v1/test/approval-holds/order-hold").exchange())
                .hasStatusOk().bodyJson().extractingPath("$.state").isEqualTo("HELD")
        }
        assertNoMoreWebhooks("order-hold", 0)
        assertEquals("PENDING", statusOf(transactionId))

        assertThat(mockMvcTester.post().uri("/v1/test/approval-holds/order-hold/release").exchange()).hasStatus(HttpStatus.ACCEPTED)

        assertEquals("PAYMENT_APPROVED", awaitWebhooks("order-hold", 1).single().type)
        await.atMost(Duration.ofSeconds(3)).untilAsserted { assertEquals("APPROVED", statusOf(transactionId)) }
        assertThat(mockMvcTester.post().uri("/v1/test/approval-holds/order-hold/release").exchange()).hasStatus(HttpStatus.CONFLICT)
        assertThat(mockMvcTester.get().uri("/v1/test/approval-holds/order-hold").exchange()).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `보류 거래를 실패 처리하면 실패 웹훅을 한 번만 보낸다`() {
        val holdPath = "/v1/test/approval-holds/order-fail-held"
        assertThat(mockMvcTester.post().uri(holdPath).exchange()).hasStatus(HttpStatus.CREATED)
        val transactionId = transactionIdOf(requestPayment("order-fail-held", scenario = "SUCCESS"))
        assertThat(mockMvcTester.get().uri(holdPath).exchange())
            .hasStatusOk().bodyJson().extractingPath("$.state").isEqualTo("HELD")

        assertThat(mockMvcTester.post().uri("$holdPath/fail").exchange()).hasStatus(HttpStatus.ACCEPTED)
        assertThat(mockMvcTester.post().uri("$holdPath/fail").exchange()).hasStatus(HttpStatus.CONFLICT)
        assertThat(mockMvcTester.post().uri("$holdPath/release").exchange()).hasStatus(HttpStatus.CONFLICT)

        val webhook = awaitWebhooks("order-fail-held", 1).single()
        assertThat(webhook.type).isEqualTo("PAYMENT_FAILED")
        assertThat(webhook.json.get("transactionId").asString()).isEqualTo(transactionId)
        assertThat(statusOf(transactionId)).isEqualTo("FAILED")
        assertNoMoreWebhooks("order-fail-held", 1)
    }

    @Test
    fun `보류를 등록하지 않은 주문은 그대로 승인된다`() {
        requestPayment("order-nohold", scenario = "SUCCESS")

        assertEquals("PAYMENT_APPROVED", awaitWebhooks("order-nohold", 1).single().type)
    }

    @Test
    fun `보류 중에 재등록해도 상태는 HELD이고 같은 주문의 다른 멱등 키 거래가 기존 보류를 덮어쓰지 않는다`() {
        mockMvcTester.post().uri("/v1/test/approval-holds/order-twice").exchange()
        val held = transactionIdOf(requestPayment("order-twice", key = "key-a", scenario = "SUCCESS"))
        await.atMost(Duration.ofSeconds(3)).untilAsserted {
            assertThat(mockMvcTester.get().uri("/v1/test/approval-holds/order-twice").exchange())
                .hasStatusOk().bodyJson().extractingPath("$.state").isEqualTo("HELD")
        }

        assertThat(mockMvcTester.post().uri("/v1/test/approval-holds/order-twice").exchange())
            .hasStatusOk().bodyJson().extractingPath("$.state").isEqualTo("HELD")
        val other = transactionIdOf(requestPayment("order-twice", key = "key-b", scenario = "SUCCESS"))
        // 다른 거래는 보류되지 않으므로 먼저 승인되고, 원래 거래는 해제할 때까지 PENDING으로 남는다
        await.atMost(Duration.ofSeconds(3)).untilAsserted { assertEquals("APPROVED", statusOf(other)) }
        assertEquals("PENDING", statusOf(held))

        assertThat(mockMvcTester.post().uri("/v1/test/approval-holds/order-twice/release").exchange()).hasStatus(HttpStatus.ACCEPTED)

        await.atMost(Duration.ofSeconds(3)).untilAsserted { assertEquals("APPROVED", statusOf(held)) }
        assertEquals(setOf(held, other), awaitWebhooks("order-twice", 2).map { it.json.get("transactionId").asString() }.toSet())
        assertNoMoreWebhooks("order-twice", 2)
    }
}
