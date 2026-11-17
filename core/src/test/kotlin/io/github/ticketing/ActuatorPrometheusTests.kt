package io.github.ticketing

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.assertj.MockMvcTester

// IntegrationTestSupport가 켜는 batch 프로필은 애플리케이션 이름을 core-batch로 바꾸므로 core로 되돌린다
@TestPropertySource(properties = ["spring.application.name=core"])
@AutoConfigureMetrics
class ActuatorPrometheusTests(@Autowired private val mockMvcTester: MockMvcTester) : IntegrationTestSupport() {

    @Test
    fun `인증 없이 prometheus 지표를 조회할 수 있다`() {
        // http.server.requests 지표를 만들기 위해 API 요청을 먼저 한 번 보낸다
        mockMvcTester.get().uri("/api/v1/performances").exchange()

        mockMvcTester.get().uri("/actuator/prometheus")
            .assertThat()
            .hasStatus(HttpStatus.OK)
            .bodyText().apply {
                contains("hikaricp_connections")
                contains("http_server_requests")
                contains("application=\"core\"")
            }
    }
}
