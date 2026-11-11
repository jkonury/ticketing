package io.github.ticketing.realtime.queue

import io.github.ticketing.realtime.RealtimeIntegrationTestSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.ParameterizedTypeReference
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

@TestPropertySource(
    properties = ["ticketing.realtime.queue.position-poll-interval=50ms", "ticketing.realtime.queue.heartbeat-interval=50ms"],
)
class QueuePositionStreamTests(client: WebTestClient, redis: ReactiveStringRedisTemplate, private val admission: AdmissionService) :
    RealtimeIntegrationTestSupport(client, redis) {
    @Test
    fun `순번 변화와 입장 이벤트를 보내고 종료한다`() = runTest {
        withContext(Dispatchers.Default) {
            val schedule = 301L
            prepareSchedule(schedule)
            enter(schedule, 1)
            enter(schedule, 2)
            coroutineScope {
                val events = stream(schedule, 2).asFlow().produceIn(this)
                assertThat(nextEvent(events, "position").data()).contains("\"position\":2")
                clientFor(1).delete().uri("/api/v1/queues/$schedule/entries/me").exchange().expectStatus().isNoContent
                assertThat(nextEvent(events, "position").data()).contains("\"position\":1")

                assertThat(admission.admit(schedule)).isEqualTo(1)
                val admitted = nextEvent(events, "admitted")
                val token = redis.opsForValue().get(QueueKeys.admitted(schedule, 2)).awaitSingle()
                assertThat(admitted.data()).contains(token).contains("expiresAt")
                assertThat(withTimeout(5000) { events.receiveCatching().isClosed }).isTrue()
                clientFor(2).get().uri("/api/v1/queues/$schedule/entries/me").exchange().expectBody()
                    .jsonPath("$.admissionToken").isEqualTo(token)
                val already = stream(schedule, 2).asFlow().produceIn(this)
                assertThat(nextEvent(already, "admitted").data()).contains(token)
                assertThat(withTimeout(5000) { already.receiveCatching().isClosed }).isTrue()
            }
        }
    }

    @Test
    fun `heartbeat는 신호를 갱신하며 연결 취소는 대기열을 유지한다`() = runTest {
        withContext(Dispatchers.Default) {
            val schedule = 302L
            prepareSchedule(schedule)
            enter(schedule, 1)
            coroutineScope {
                val events = stream(schedule, 1).asFlow().produceIn(this)
                nextEvent(events, "position")
                redis.opsForZSet().add(QueueKeys.seen(schedule), "1", 1.0).awaitSingle()
                nextHeartbeat(events)
                assertThat(redis.opsForZSet().score(QueueKeys.seen(schedule), "1").awaitSingle())
                    .isEqualTo(now.toEpochMilli().toDouble())
                events.cancel()
            }
            assertThat(redis.opsForZSet().rank(QueueKeys.waiting(schedule), "1").awaitSingle()).isZero()
        }
    }

    @Test
    fun `이탈하면 removed를 보내고 종료한다`() = runTest {
        withContext(Dispatchers.Default) {
            val schedule = 303L
            prepareSchedule(schedule)
            enter(schedule, 1)
            coroutineScope {
                val events = stream(schedule, 1).asFlow().produceIn(this)
                nextEvent(events, "position")
                clientFor(1).delete().uri("/api/v1/queues/$schedule/entries/me").exchange().expectStatus().isNoContent
                nextEvent(events, "removed")
                assertThat(withTimeout(5000) { events.receiveCatching().isClosed }).isTrue()
            }
        }
    }

    @Test
    fun `등록하지 않은 회원과 헤더 누락 및 없는 회차는 HTTP 오류다`() = runTest {
        val schedule = 304L
        prepareSchedule(schedule)
        clientFor(1).get().uri("/api/v1/streams/queues/$schedule").exchange().expectStatus().isNotFound.expectBody()
            .jsonPath("$.code").isEqualTo("QUEUE_ENTRY_NOT_FOUND")
        client.get().uri("/api/v1/streams/queues/$schedule").exchange().expectStatus().isUnauthorized.expectBody()
            .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
        clientFor(1).get().uri("/api/v1/streams/queues/305").exchange().expectStatus().isNotFound.expectBody()
            .jsonPath("$.code").isEqualTo("SCHEDULE_NOT_FOUND")
    }

    private fun enter(schedule: Long, member: Long) {
        clientFor(member).post().uri("/api/v1/queues/$schedule/entries").exchange().expectStatus().isOk
    }

    private fun stream(schedule: Long, member: Long) = clientFor(member).get().uri("/api/v1/streams/queues/$schedule")
        .exchange().expectStatus().isOk.returnResult(object : ParameterizedTypeReference<ServerSentEvent<String>>() {}).responseBody

    private suspend fun nextEvent(events: ReceiveChannel<ServerSentEvent<String>>, name: String): ServerSentEvent<String> =
        withTimeout(5000) {
            while (true) {
                val event = events.receive()
                if (event.event() == name) return@withTimeout event
            }
            error("unreachable")
        }

    private suspend fun nextHeartbeat(events: ReceiveChannel<ServerSentEvent<String>>) {
        withTimeout(5000) {
            do {
                val event = events.receive()
            } while (event.comment() != "heartbeat")
        }
    }
}
