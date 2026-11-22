package io.github.ticketing.gateway.route

import io.github.ticketing.gateway.GatewayIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

class GatewayRoutingTests(
    client: WebTestClient,
    private val redis: ReactiveStringRedisTemplate,
    @param:LocalServerPort private val port: Int,
) : GatewayIntegrationTestSupport(client) {
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
    fun `스트림 요청은 티켓 회원 헤더로 실시간 앱에 전달된다`() {
        val ticket = UUID.randomUUID().toString()
        redis.opsForValue().set("sse:ticket:$ticket", "42", Duration.ofSeconds(30)).block()
        client.get().uri("/api/v1/streams/1?ticket=$ticket").header("X-Member-Id", "999").exchange().expectStatus().isOk

        assertThat(realtime.requests.single().headers.getFirst("X-Member-Id")).isEqualTo("42")
    }

    @Test
    fun `결제 조회는 payment 앱에 그대로 전달한다`() {
        val jwt = token()
        client.get().uri("/api/v1/payments/me?bookingIds=1").headers { it.setBearerAuth(jwt) }.exchange().expectStatus().isOk

        assertThat(payment.requests).hasSize(1)
        assertThat(payment.requests.single().path).isEqualTo("api/v1/payments/me")
        assertThat(payment.requests.single().query).isEqualTo("bookingIds=1")
        assertThat(payment.requests.single().headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer $jwt")
        assertThat(payment.requests.single().headers.getFirst("X-Member-Id")).isNull()
        assertThat(core.requests).isEmpty()
    }

    @Test
    fun `예매 결제 요청은 코어에 전달한다`() {
        client.post().uri("/api/v1/bookings/1/payments").headers { it.setBearerAuth(token()) }.exchange().expectStatus().isOk

        assertThat(core.requests).hasSize(1)
        assertThat(payment.requests).isEmpty()
    }

    @Test
    fun `정규화하면 결제 웹훅인 경로는 어디에도 전달하지 않고 404로 막는다`() {
        val webhookPaths = listOf(
            "/api/v1/payments/webhooks/fake-pg",
            "/api/v1/payments/webhooks/other",
            "/api/v1/payments/webhooks",
            "/api/v1/payments/webhooks/fake-pg/",
            "/API/V1/PAYMENTS/WEBHOOKS/FAKE-PG",
            "/api/v1/payments/%77ebhooks/fake-pg",
        )
        for (path in webhookPaths) {
            client.post().uri(rawUri(path)).exchange().expectStatus().isNotFound.expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND")
        }

        assertNothingForwarded()
    }

    @Test
    fun `웹훅 경로는 잘못된 Bearer 토큰이 있어도 401이 아니라 404로 막는다`() {
        client.post().uri("/api/v1/payments/webhooks/fake-pg").headers { it.setBearerAuth("malformed") }
            .exchange().expectStatus().isNotFound.expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND")

        assertNothingForwarded()
    }

    @Test
    fun `모호한 경로는 라우트 선택 전에 400으로 거부한다`() {
        val ambiguousPaths = listOf(
            "//api/v1/payments//webhooks/fake-pg",
            "/api/v1/payments//webhooks/fake-pg",
            "/api/v1/payments/webhooks%2Ffake-pg",
            "/api/v1/payments%2Fwebhooks/fake-pg",
            "/api/v1/payments/webhooks%5Cfake-pg",
            "/api/v1/payments/webhooks/%2e%2e/me",
            "/api/v1/payments/webhooks/./fake-pg",
            "/api/v1/payments/webhooks;x=1/fake-pg",
            "/api/v1/payments/me//",
            "/api/v1//bookings/me",
        )
        for (path in ambiguousPaths) {
            client.post().uri(rawUri(path)).exchange().expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("INVALID_PATH")
        }

        assertNothingForwarded()
    }

    @Test
    fun `후행 슬래시와 인코딩된 일반 세그먼트는 기존대로 전달한다`() {
        client.get().uri(rawUri("/api/v1/bookings/me/")).exchange().expectStatus().isOk
        client.get().uri(rawUri("/api/v1/payments/%6De")).exchange().expectStatus().isOk

        assertThat(core.requests).hasSize(1)
        assertThat(payment.requests).hasSize(1)
    }

    private fun rawUri(path: String) = URI.create("http://localhost:$port$path")

    private fun assertNothingForwarded() {
        assertThat(core.requests).isEmpty()
        assertThat(payment.requests).isEmpty()
        assertThat(realtime.requests).isEmpty()
    }

    @Test
    fun `결제 조회에도 rate limit을 적용한다`() {
        val statuses = (1..3).map { client.get().uri("/api/v1/payments/me").exchange().returnResult<String>().status.value() }

        assertThat(statuses).containsExactly(200, 200, 429)
    }
}
