package io.github.ticketing.gateway.route

import io.github.ticketing.gateway.GatewayIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val CONCURRENT_REQUESTS = 30
private val RESPONSE_DELAY = Duration.ofMillis(80)

@TestPropertySource(properties = ["ticketing.gateway.core-pool.max-connections=1"])
class ConnectionPoolLimitTests(private val webClient: WebTestClient) : GatewayIntegrationTestSupport(webClient) {
    @Test
    fun `core 상한보다 많은 동시 요청도 거절 없이 모두 성공하고 동시 연결은 상한을 넘지 않는다`() {
        core.responseDelay = RESPONSE_DELAY

        val results = concurrentGet { "/api/v1/bookings/me" }

        assertThat(results).hasSize(CONCURRENT_REQUESTS).allMatch { it == 200 }
        assertThat(core.maxConcurrentConnections).isEqualTo(1)
    }

    @Test
    fun `core가 아닌 호스트에는 상한이 걸리지 않는다`() {
        realtime.responseDelay = RESPONSE_DELAY

        val results = concurrentGet { "/api/v1/queues/1/entries" }

        assertThat(results).hasSize(CONCURRENT_REQUESTS).allMatch { it == 200 }
        assertThat(realtime.maxConcurrentConnections).isGreaterThan(1)
    }

    private fun concurrentGet(path: () -> String): List<Int> {
        val executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)
        val startGate = CountDownLatch(1)
        val futures =
            (1..CONCURRENT_REQUESTS).map { index ->
                executor.submit<Int> {
                    startGate.await()
                    webClient.get().uri(path()).headers { it.setBearerAuth(token(subject = (1000 + index).toString())) }
                        .exchange().returnResult(String::class.java).status.value()
                }
            }
        startGate.countDown()
        val results = futures.map { it.get(60, TimeUnit.SECONDS) }
        executor.shutdown()
        return results
    }
}
