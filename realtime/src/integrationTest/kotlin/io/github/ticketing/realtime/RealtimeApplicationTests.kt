package io.github.ticketing.realtime

import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.test.web.reactive.server.WebTestClient

class RealtimeApplicationTests(client: WebTestClient, redis: ReactiveStringRedisTemplate) : RealtimeIntegrationTestSupport(client, redis) {
    @Test
    fun `컨텍스트가 뜬다`() = Unit
}
