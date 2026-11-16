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
    private val credentials = WarmupCredentials("jwt", "key", "admission")

    @Test
    fun `동시 호출 상한을 지키고 이전 회차가 끝난 뒤 다음 회차를 실행한다`() {
        val counts = WarmupTrafficCounts()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val started = AtomicInteger()
        val seen = java.util.concurrent.ConcurrentHashMap<WarmupKind, Int>()
        val properties = GatewayWarmupProperties(rounds = 2, batchSize = 20, concurrency = 4)
        val http = WebClient.builder().exchangeFunction(
            ExchangeFunction { request ->
                Mono.defer {
                    assertThat(counts.rounds.get()).isEqualTo(started.getAndIncrement() / properties.batchSize)
                    peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    assertThat(request.headers().getFirst(STARTUP_WARMUP_HEADER)).isEqualTo("key")
                    val kind = kindOf(request)
                    seen.merge(kind, 1, Int::plus)
                    Mono.delay(Duration.ofSeconds(1)).map {
                        active.decrementAndGet()
                        response(kind)
                    }
                }
            },
        ).build()

        StepVerifier.withVirtualTime { warmupTraffic(http, "http://localhost", credentials, properties, mapper, counts) }
            .thenAwait(Duration.ofSeconds(10)).verifyComplete()

        assertThat(peak.get()).isEqualTo(4)
        assertThat(counts.rounds.get()).isEqualTo(2)
        assertThat(counts.completed.get()).isEqualTo(40)
        assertThat(counts.winners.get()).isEqualTo(2)
        assertThat(counts.payments.get()).isEqualTo(2)
        assertThat(counts.details.get()).isEqualTo(4)
        assertThat(seen).containsEntry(WarmupKind.PAYMENT, 2).containsEntry(WarmupKind.DETAIL, 4).containsEntry(WarmupKind.WINNER, 2)
        assertThat(counts.db.get()).isEqualTo(40)
        assertThat(counts.redis.get()).isEqualTo(80)
    }

    @Test
    fun `연결이 끊겨 실패한 요청은 상한 안에서 다시 보내고 계속 실패하면 중단한다`() {
        val counts = WarmupTrafficCounts()
        val calls = AtomicInteger()
        val http = WebClient.builder().exchangeFunction(
            ExchangeFunction { request ->
                // 첫 호출만 서버 오류(연결 끊김이 Gateway에서 500이 된 경우)이고 이후는 정상이다
                if (calls.getAndIncrement() == 0) {
                    Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build())
                } else {
                    Mono.just(response(kindOf(request)))
                }
            },
        ).build()
        val properties = GatewayWarmupProperties(rounds = 1, batchSize = 20, concurrency = 1)

        StepVerifier.create(warmupTraffic(http, "http://localhost", credentials, properties, mapper, counts)).verifyComplete()

        assertThat(counts.retries.get()).isEqualTo(1)
        assertThat(counts.completed.get()).isEqualTo(20)

        val alwaysFailing = WebClient.builder().exchangeFunction(
            ExchangeFunction { Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build()) },
        ).build()
        val failing = WarmupTrafficCounts()

        StepVerifier.create(warmupTraffic(alwaysFailing, "http://localhost", credentials, properties, mapper, failing))
            .expectErrorMatches { it.message?.startsWith("Retries exhausted") == true }.verify()

        assertThat(failing.retries.get()).isEqualTo(2)
        assertThat(failing.completed.get()).isZero()
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
            warmupTraffic(http, "http://localhost", credentials, properties, mapper, counts).timeout(Duration.ofSeconds(1))
        }.thenAwait(Duration.ofSeconds(1)).expectError(TimeoutException::class.java).verify()

        assertThat(cancelled.get()).isEqualTo(4)
        assertThat(counts.rounds.get()).isZero()
        assertThat(counts.completed.get()).isZero()
    }

    // 요청 경로·메서드·멱등 키만 보고 종류를 되짚는다. 입장 토큰은 예매 생성에만 붙는다.
    private fun kindOf(request: org.springframework.web.reactive.function.client.ClientRequest): WarmupKind {
        val path = request.url().path
        return when {
            path.endsWith("/payments") -> {
                assertThat(request.headers().getFirst("X-Admission-Token")).isNull()
                WarmupKind.PAYMENT
            }

            path == "/api/v1/bookings/-1" -> WarmupKind.DETAIL

            else -> {
                assertThat(path).isEqualTo("/api/v1/bookings")
                assertThat(request.headers().getFirst("X-Admission-Token")).isEqualTo("admission")
                if (request.headers().getFirst("Idempotency-Key") == WARMUP_WINNER_KEY) WarmupKind.WINNER else WarmupKind.CONFLICT
            }
        }
    }

    @Test
    fun `한 회차 안에서 예매 생성 결제 요청 상세 조회를 섞고 비율은 실제 흐름을 따른다`() {
        val kinds = (0 until 1000).map { warmupKind(it) }.groupingBy { it }.eachCount()

        assertThat(kinds.getValue(WarmupKind.PAYMENT)).isEqualTo(50)
        assertThat(kinds.getValue(WarmupKind.WINNER)).isEqualTo(50)
        assertThat(kinds.getValue(WarmupKind.DETAIL)).isBetween(75, 95)
        assertThat(kinds.getValue(WarmupKind.CONFLICT)).isGreaterThan(800)
        assertThat((0 until 1000).map { warmupKind(it) }.take(60).distinct()).hasSize(4)
        assertThat((0 until 1000).map { warmupSeatCount(it) }.distinct().sorted()).containsExactly(1, 2, 3, 4)
    }

    private fun response(kind: WarmupKind): ClientResponse = ClientResponse.create(
        when (kind) {
            WarmupKind.WINNER -> HttpStatus.CREATED
            WarmupKind.CONFLICT -> HttpStatus.CONFLICT
            WarmupKind.PAYMENT -> HttpStatus.ACCEPTED
            WarmupKind.DETAIL -> HttpStatus.OK
        },
    ).header(STARTUP_WARMUP_IO_HEADER, "db=1;redis=2").header("Content-Type", "application/json")
        .body(
            when (kind) {
                WarmupKind.WINNER -> """{"bookingId":1,"status":"PENDING"}"""
                WarmupKind.CONFLICT -> """{"code":"SEAT_ALREADY_HELD"}"""
                WarmupKind.PAYMENT -> """{"bookingId":1,"status":"PENDING"}"""
                WarmupKind.DETAIL -> """{"status":"PENDING"}"""
            },
        ).build()
}
