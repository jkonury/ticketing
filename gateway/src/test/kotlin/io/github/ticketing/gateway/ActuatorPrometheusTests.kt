package io.github.ticketing.gateway

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.test.web.reactive.server.WebTestClient

@AutoConfigureMetrics
class ActuatorPrometheusTests(client: WebTestClient) : GatewayIntegrationTestSupport(client) {
    @Test
    fun `인증 없이 prometheus 지표를 조회할 수 있다`() {
        client.get().uri("/api/v1/bookings/me").exchange().expectStatus().isOk

        val body = client.get().uri("/actuator/prometheus").exchange()
            .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody.orEmpty()

        assertThat(body).contains("application=\"gateway\"")
        assertThat(body).contains("http_server_requests")
    }
}
