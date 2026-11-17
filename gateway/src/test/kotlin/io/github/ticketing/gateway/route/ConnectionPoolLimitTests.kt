package io.github.ticketing.gateway.route

import io.github.ticketing.gateway.GatewayIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val CONCURRENT_REQUESTS = 30

@TestPropertySource(properties = ["spring.cloud.gateway.server.webflux.httpclient.pool.max-connections=1"])
class ConnectionPoolLimitTests(private val webClient: WebTestClient) : GatewayIntegrationTestSupport(webClient) {
    @Test
    fun `풀 상한보다 많은 동시 요청도 거절 없이 모두 성공한다`() {
        val executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)
        val startGate = CountDownLatch(1)

        val statuses =
            (1..CONCURRENT_REQUESTS).map { index ->
                executor.submit<Int> {
                    startGate.await()
                    webClient.mutate().defaultHeader("X-Forwarded-For", "10.9.9.$index").build()
                        .get().uri("/api/v1/bookings/me").exchange().returnResult(String::class.java).status.value()
                }
            }
        startGate.countDown()
        val results = statuses.map { it.get(30, TimeUnit.SECONDS) }
        executor.shutdown()

        assertThat(results).hasSize(CONCURRENT_REQUESTS).allMatch { it == 200 }
    }
}
