package io.github.ticketing.realtime

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.test.web.reactive.server.WebTestClient

@AutoConfigureMetrics
class ActuatorPrometheusTests(client: WebTestClient, redis: ReactiveStringRedisTemplate) : RealtimeIntegrationTestSupport(client, redis) {
    @Test
    fun `인증 없이 prometheus 지표를 조회할 수 있다`() {
        val body = client.get().uri("/actuator/prometheus").exchange()
            .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody.orEmpty()

        assertThat(body).contains("application=\"realtime\"")
        assertThat(body).contains("jvm_memory")
    }
}
