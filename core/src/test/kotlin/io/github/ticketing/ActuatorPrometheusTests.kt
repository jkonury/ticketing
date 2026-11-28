package io.github.ticketing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.assertj.MockMvcTester

// IntegrationTestSupport가 켜는 batch 프로필은 애플리케이션 이름을 core-batch로 바꾸므로 core로 되돌린다
@TestPropertySource(properties = ["spring.application.name=core"])
@AutoConfigureMetrics
class ActuatorPrometheusTests(private val mockMvcTester: MockMvcTester, private val beanFactory: ListableBeanFactory) :
    IntegrationTestSupport() {

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

    @Test
    fun `batch 프로필에서는 같은 DB의 배치 지표를 중복 수집하지 않는다`() {
        assertThat(beanFactory.getBeansOfType(BatchJobMetrics::class.java)).isEmpty()
    }
}
