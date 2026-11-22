package io.github.ticketing.realtime.seat

import io.github.ticketing.realtime.RealtimeIntegrationTestSupport
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.reactivestreams.Subscription
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.BaseSubscriber

@TestPropertySource(
    properties = [
        "ticketing.realtime.queue.heartbeat-interval=100ms",
        "ticketing.realtime.queue.seat-stream-buffer=4",
    ],
)
class SeatStreamOverflowTests(
    client: WebTestClient,
    redis: ReactiveStringRedisTemplate,
    private val controller: SeatStatusStreamController,
    private val meters: MeterRegistry,
) : RealtimeIntegrationTestSupport(client, redis) {
    @Test
    fun `소비가 완전히 멈춘 구독자는 버퍼 초과 후 스트림이 종료된다`() = runTest {
        withContext(Dispatchers.Default) {
            val schedule = 501L
            prepareSchedule(schedule)
            val before = meters.counter("seat.stream.overflow").count()
            val first = CompletableDeferred<Unit>()
            val terminated = CompletableDeferred<Throwable?>()
            // WebFlux처럼 reactive 구독자가 첫 원소만 요청하고 더 요청하지 않아 downstream 수요가 끊긴 연결을 흉내 낸다
            controller.stream(schedule, "1", null, null).asFlux().subscribe(
                object : BaseSubscriber<ServerSentEvent<Any>>() {
                    override fun hookOnSubscribe(subscription: Subscription) = subscription.request(1)

                    override fun hookOnNext(value: ServerSentEvent<Any>) {
                        first.complete(Unit)
                    }

                    override fun hookOnComplete() {
                        terminated.complete(null)
                    }

                    override fun hookOnError(throwable: Throwable) {
                        terminated.complete(throwable)
                    }
                },
            )
            withTimeout(WAIT_MILLIS) { first.await() }
            // channelFlow 기본 버퍼(64)와 연결 버퍼(4)를 모두 넘기는 양
            repeat(EVENT_COUNT) { add(schedule) }
            assertThat(withTimeout(WAIT_MILLIS) { terminated.await() }).isNull()
            assertThat(meters.counter("seat.stream.overflow").count()).isEqualTo(before + 1)
        }
    }

    private suspend fun add(schedule: Long) = redis.opsForStream<String, String>().add(
        StreamRecords.mapBacked<String, String, String>(
            mapOf("seatIds" to "1,2", "status" to "SOLD", "at" to "100"),
        ).withStreamKey(streamKey(schedule)),
    ).awaitSingle()

    private companion object {
        const val WAIT_MILLIS = 30_000L
        const val EVENT_COUNT = 300
    }
}
