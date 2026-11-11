package io.github.ticketing.gateway

import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient

class GatewayApplicationTests(client: WebTestClient) : GatewayIntegrationTestSupport(client) {
    @Test
    fun `컨텍스트가 뜬다`() = Unit
}
