package io.github.ticketing.gateway.sse

import io.github.ticketing.gateway.GatewayIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SseTicketFilterTests(client: WebTestClient, private val redis: ReactiveStringRedisTemplate) :
    GatewayIntegrationTestSupport(client) {
    private fun ticket(member: String = "42"): String {
        val value = UUID.randomUUID().toString()
        redis.opsForValue().set("sse:ticket:$value", member, Duration.ofSeconds(30)).block()
        return value
    }

    @Test
    fun `티켓을 한 번 소비하고 회원과 재연결 정보를 전달한다`() {
        val ticket = ticket()

        client.get().uri("/api/v1/streams/1?ticket=$ticket&lastEventId=123")
            .header("X-Member-Id", "99").header("Last-Event-ID", "456")
            .headers { it.setBearerAuth(token(subject = "88")) }
            .exchange().expectStatus().isOk

        assertThat(realtime.requests).hasSize(1)
        assertThat(realtime.requests.single().headers.getFirst("X-Member-Id")).isEqualTo("42")
        assertThat(realtime.requests.single().headers.getFirst("Last-Event-ID")).isEqualTo("456")
        assertThat(realtime.requests.single().query).isEqualTo("lastEventId=123")
        assertThat(redis.hasKey("sse:ticket:$ticket").block()).isFalse()

        client.get().uri("/api/v1/streams/1?ticket=$ticket").exchange()
            .expectStatus().isUnauthorized.expectBody().jsonPath("$.code").isEqualTo("SSE_TICKET_INVALID")
        assertThat(realtime.requests).hasSize(1)
    }

    @Test
    fun `같은 티켓으로 동시에 연결하면 한 요청만 성공한다`() {
        // 다른 테스트의 회원 42 한도 버킷과 섞이지 않도록 고유 회원을 쓴다
        val ticket = ticket(UUID.randomUUID().mostSignificantBits.ushr(1).toString())
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val statuses = (1..2).map {
                executor.submit<Pair<Int, String?>> {
                    start.await()
                    client.get().uri("/api/v1/streams/1?ticket=$ticket").exchange().returnResult<String>().let {
                        it.status.value() to
                            it.responseBody.blockFirst(Duration.ofSeconds(5))
                    }
                }
            }
            start.countDown()

            val results = statuses.map { it.get(10, TimeUnit.SECONDS) }
            assertThat(results.map { it.first }).containsExactlyInAnyOrder(200, 401)
            assertThat(results.single { it.first == 401 }.second).contains("\"code\":\"SSE_TICKET_INVALID\"")
        } finally {
            executor.shutdownNow()
        }

        assertThat(realtime.requests).hasSize(1)
        assertThat(redis.hasKey("sse:ticket:$ticket").block()).isFalse()
    }

    @Test
    fun `티켓이 없거나 유효하지 않으면 거부한다`() {
        val missing = UUID.randomUUID().toString()
        val nonNumeric = ticket("invalid")

        listOf("/api/v1/streams/1", "/api/v1/streams/1?ticket=$missing", "/api/v1/streams/1?ticket=$nonNumeric")
            .forEach { uri ->
                client.get().uri(uri).exchange().expectStatus().isUnauthorized
                    .expectBody().jsonPath("$.code").isEqualTo("SSE_TICKET_INVALID")
            }

        assertThat(realtime.requests).isEmpty()
    }

    @Test
    fun `SSE 경로에만 CORS preflight를 허용한다`() {
        client.options().uri("/api/v1/streams/1")
            .header(HttpHeaders.ORIGIN, "http://localhost:13000")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
            .exchange().expectStatus().isOk
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:13000")
            .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)

        client.options().uri("/api/v1/bookings/me")
            .header(HttpHeaders.ORIGIN, "http://localhost:13000")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
            .exchange().expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
    }
}
