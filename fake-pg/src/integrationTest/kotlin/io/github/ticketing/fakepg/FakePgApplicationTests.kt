package io.github.ticketing.fakepg

import io.github.ticketing.fakepg.payment.FakePgTestSupport
import io.github.ticketing.fakepg.payment.MutableClock
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc

class FakePgApplicationTests(mockMvc: MockMvc, clock: MutableClock) : FakePgTestSupport(mockMvc, clock) {
    @Test
    fun `컨텍스트가 뜬다`() = Unit
}
