package io.github.ticketing.gateway

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient

class TracePropagationTests(client: WebTestClient) : GatewayIntegrationTestSupport(client) {
    @Test
    fun `하위 서버로 traceparent를 전달하고 클라이언트의 traceId를 유지한다`() {
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"

        client.get().uri("/api/v1/bookings/me")
            .header("traceparent", "00-$traceId-00f067aa0ba902b7-01")
            .exchange().expectStatus().isOk

        val traceparent = core.requests.single().headers.getFirst("traceparent")
        assertThat(traceparent).isNotNull().matches("00-$traceId-[0-9a-f]{16}-0[01]")
    }

    @Test
    fun `realtime 경로에도 traceparent를 전달한다`() {
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"

        client.get().uri("/api/v1/queues/1/entries")
            .header("traceparent", "00-$traceId-00f067aa0ba902b7-01")
            .headers { it.setBearerAuth(token()) }
            .exchange().expectStatus().isOk

        assertThat(realtime.requests.single().headers.getFirst("traceparent")).isNotNull().contains(traceId)
    }
}
