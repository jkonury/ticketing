package io.github.ticketing.gateway.warmup

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

internal class GatewayWarmupTrafficTests {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `동시 호출 상한을 지키고 이전 회차가 끝난 뒤 다음 회차를 실행한다`() {
        val counts = WarmupTrafficCounts()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val started = AtomicInteger()
        val properties = GatewayWarmupProperties(rounds = 2, batchSize = 20, concurrency = 4)
        val http = WebClient.builder().exchangeFunction(
            ExchangeFunction { request ->
                Mono.defer {
                    assertThat(counts.rounds.get()).isEqualTo(started.getAndIncrement() / properties.batchSize)
                    peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    val winner = request.url().query == "conflict=false"
                    Mono.delay(Duration.ofSeconds(1)).map {
                        active.decrementAndGet()
                        response(winner)
                    }
                }
            },
        ).build()

        StepVerifier.withVirtualTime { warmupTraffic(http, "http://localhost", "token", properties, mapper, counts) }
            .thenAwait(Duration.ofSeconds(10)).verifyComplete()

        assertThat(peak.get()).isEqualTo(4)
        assertThat(counts.rounds.get()).isEqualTo(2)
        assertThat(counts.completed.get()).isEqualTo(40)
        assertThat(counts.winners.get()).isEqualTo(2)
        assertThat(counts.db.get()).isEqualTo(40)
        assertThat(counts.redis.get()).isEqualTo(80)
    }

    @Test
    fun `전체 상한으로 취소되면 진행 중 요청과 다음 회차를 중단한다`() {
        val counts = WarmupTrafficCounts()
        val cancelled = AtomicInteger()
        val http = WebClient.builder().exchangeFunction(
            ExchangeFunction {
                Mono.never<ClientResponse>().doOnCancel { cancelled.incrementAndGet() }
            },
        ).build()
        val properties = GatewayWarmupProperties(rounds = 2, batchSize = 20, concurrency = 4)

        StepVerifier.withVirtualTime {
            warmupTraffic(http, "http://localhost", "token", properties, mapper, counts).timeout(Duration.ofSeconds(1))
        }.thenAwait(Duration.ofSeconds(1)).expectError(TimeoutException::class.java).verify()

        assertThat(cancelled.get()).isEqualTo(4)
        assertThat(counts.rounds.get()).isZero()
        assertThat(counts.completed.get()).isZero()
    }

    private fun response(winner: Boolean): ClientResponse = ClientResponse.create(if (winner) HttpStatus.CREATED else HttpStatus.CONFLICT)
        .body("""{"winner":$winner,"dbStatements":1,"redisCommands":2}""").build()
}
