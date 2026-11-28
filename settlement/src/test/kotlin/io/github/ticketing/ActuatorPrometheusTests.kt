package io.github.ticketing

import org.junit.jupiter.api.Test
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester

@AutoConfigureMetrics
class ActuatorPrometheusTests(private val mockMvcTester: MockMvcTester) : SettlementWebTestSupport() {

    @Test
    fun `인증 없이 정산 애플리케이션의 prometheus 지표를 조회할 수 있다`() {
        val response = mockMvcTester.get().uri("/actuator/prometheus").exchange()

        response.assertThat()
            .hasStatus(HttpStatus.OK)
            .bodyText()
            .contains("application=\"settlement\"", "hikaricp_connections")
            .doesNotContain("ticketing_event_publication")
    }
}
