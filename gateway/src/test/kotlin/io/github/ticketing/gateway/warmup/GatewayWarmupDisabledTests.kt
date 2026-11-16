package io.github.ticketing.gateway.warmup

import io.github.ticketing.gateway.GatewayIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient

class GatewayWarmupDisabledTests(client: WebTestClient) : GatewayIntegrationTestSupport(client) {
    @Test
    fun `기본 설정은 예열 경로를 core로 전달하지 않는다`() {
        client.post().uri("/internal/startup-warmup").headers { it.setBearerAuth(token()) }.exchange().expectStatus().isNotFound

        assertThat(core.requests).isEmpty()
    }

    @Test
    fun `외부에서 보낸 예열 자격 헤더는 core로 전달하지 않는다`() {
        client.get().uri("/api/v1/performances").header(STARTUP_WARMUP_HEADER, "forged").exchange()

        assertThat(core.requests).hasSize(1)
        assertThat(core.requests.single().headers.getFirst(STARTUP_WARMUP_HEADER)).isNull()
    }
}
