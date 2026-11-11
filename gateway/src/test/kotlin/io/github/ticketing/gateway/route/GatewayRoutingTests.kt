package io.github.ticketing.gateway.route

import io.github.ticketing.gateway.GatewayIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant

class GatewayRoutingTests(client: WebTestClient) : GatewayIntegrationTestSupport(client) {
    @Test
    fun `코어 요청은 위조 회원 헤더 없이 전달된다`() {
        client.get().uri("/api/v1/bookings/me").header("X-Member-Id", "999").exchange().expectStatus().isOk

        assertThat(core.requests).hasSize(1)
        assertThat(core.requests.single().path).isEqualTo("api/v1/bookings/me")
        assertThat(core.requests.single().headers.getFirst("X-Member-Id")).isNull()
        assertThat(realtime.requests).isEmpty()
    }

    @Test
    fun `인증된 대기열 요청은 검증한 회원 헤더로 전달된다`() {
        client.get().uri("/api/v1/queues/1/entries").header("X-Member-Id", "999").headers { it.setBearerAuth(token()) }
            .exchange().expectStatus().isOk

        assertThat(realtime.requests).hasSize(1)
        assertThat(realtime.requests.single().path).isEqualTo("api/v1/queues/1/entries")
        assertThat(realtime.requests.single().headers.getFirst("X-Member-Id")).isEqualTo("42")
        assertThat(core.requests).isEmpty()
    }

    @Test
    fun `대기열 요청에는 인증이 필요하다`() {
        client.get().uri("/api/v1/queues/1/entries").header("X-Member-Id", "999").exchange()
            .expectStatus().isUnauthorized.expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED")

        assertThat(realtime.requests).isEmpty()
    }

    @Test
    fun `잘못된 서명과 만료된 토큰은 경로와 관계없이 거부한다`() {
        val invalidTokens =
            listOf(
                token(secret = "different-secret-different-secret-012345"),
                token(expiresAt = Instant.now().minusSeconds(1)),
                "malformed",
            )
        for (path in listOf("/api/v1/bookings/me", "/api/v1/queues/1/entries", "/api/v1/streams/1")) {
            for (invalidToken in invalidTokens) {
                client.get().uri(path).headers { it.setBearerAuth(invalidToken) }.exchange()
                    .expectStatus().isUnauthorized.expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED")
            }
        }
        assertThat(core.requests).isEmpty()
        assertThat(realtime.requests).isEmpty()
    }

    @Test
    fun `유효한 토큰은 코어에 그대로 전달한다`() {
        val jwt = token()
        client.get().uri("/api/v1/bookings/me").headers { it.setBearerAuth(jwt) }.exchange().expectStatus().isOk

        assertThat(core.requests.single().headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer $jwt")
    }

    @Test
    fun `스트림 요청은 위조 회원 헤더 없이 실시간 앱에 전달된다`() {
        client.get().uri("/api/v1/streams/1").header("X-Member-Id", "999").exchange().expectStatus().isOk

        assertThat(realtime.requests.single().headers.getFirst("X-Member-Id")).isNull()
    }
}
