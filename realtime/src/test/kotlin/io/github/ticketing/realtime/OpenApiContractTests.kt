package io.github.ticketing.realtime

import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import ticketing.testing.OpenApiSnapshot

class OpenApiContractTests(client: WebTestClient, redis: ReactiveStringRedisTemplate) : RealtimeIntegrationTestSupport(client, redis) {
    @Test
    fun `HTTP API는 커밋된 OpenAPI 계약과 같다`() {
        val result = client.get().uri("/v3/api-docs").accept(MediaType.APPLICATION_JSON).exchange()

        val body = result.expectStatus().isOk.expectBody().returnResult().responseBody
        OpenApiSnapshot.verify("realtime", requireNotNull(body))
    }
}
