package io.github.ticketing.fakepg.payment

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.assertj.MockMvcTester
import kotlin.test.assertEquals

@SpringBootTest(properties = ["fake-pg.behavior.failure-rate=1.0"])
class ProbabilityScenarioTest(mockMvcTester: MockMvcTester, clock: MutableClock) : FakePgTestSupport(mockMvcTester, clock) {

    @Test
    fun `시나리오를 생략하면 failure-rate 설정대로 실패 웹훅을 보낸다`() {
        requestPayment("order-prob-fail")

        assertEquals("PAYMENT_FAILED", awaitWebhooks("order-prob-fail", 1).single().type)
    }
}
