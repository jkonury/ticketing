package io.github.ticketing.realtime.multi

import io.github.ticketing.realtime.MutableClock
import io.github.ticketing.realtime.RealtimeIntegrationTestSupport
import io.github.ticketing.realtime.queue.AdmissionService
import io.github.ticketing.realtime.queue.QueueKeys
import io.github.ticketing.realtime.seat.streamKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.ParameterizedTypeReference
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
    properties = [
        "ticketing.realtime.queue.admission-batch-size=2",
        "ticketing.realtime.queue.position-poll-interval=50ms",
        "ticketing.realtime.queue.heartbeat-interval=100ms",
    ],
)
class MultiInstanceConsistencyTests(
    client: WebTestClient,
    redis: ReactiveStringRedisTemplate,
    private val admission: AdmissionService,
    private val clock: MutableClock,
) : RealtimeIntegrationTestSupport(client, redis) {
    private lateinit var second: SecondInstance

    @BeforeAll
    fun startSecond() {
        clock.reset()
        second = SecondInstance()
        second.clock.reset()
    }

    @AfterAll
    fun closeSecond() = second.close()

    @Test
    fun `양쪽 인스턴스는 하나의 등록 순번을 공유한다`() = runTest {
        val schedule = 501L
        prepareSchedule(schedule)
        (1L..4L).forEach { member -> enter(if (member % 2L == 1L) client else second.client, schedule, member) }

        for (member in 1L..4L) {
            for (instance in listOf(client, second.client)) {
                memberClient(instance, member).get().uri(queuePath(schedule) + "/me").exchange().expectStatus().isOk.expectBody()
                    .jsonPath("$.position").isEqualTo(member.toInt())
            }
        }
    }

    @Test
    fun `동시 입장은 한 주기 배치 크기를 넘지 않고 회원마다 한 번만 입장한다`() = runTest {
        val schedule = 502L
        prepareSchedule(schedule)
        (1L..5L).forEach { enter(client, schedule, it) }

        val counts = withContext(Dispatchers.Default) {
            coroutineScope {
                val first = async { admission.admit(schedule) }
                val other = async { second.admission.admit(schedule) }
                listOf(first.await(), other.await())
            }
        }

        val tokens = (1L..5L).mapNotNull { redis.opsForValue().get(QueueKeys.admitted(schedule, it)).awaitSingleOrNull() }
        assertThat(counts.sum()).isEqualTo(2)
        assertThat(tokens).hasSize(2).doesNotHaveDuplicates()
        assertThat(redis.opsForZSet().size(QueueKeys.waiting(schedule)).awaitSingle()).isEqualTo(3)
        tokens.forEach { token ->
            assertThat(redis.opsForHash<String, String>().get(QueueKeys.admission(token), "scheduleId").awaitSingle())
                .isEqualTo(schedule.toString())
        }
    }

    @Test
    fun `한쪽에서 입장한 토큰을 다른 쪽 조회와 순번 스트림에서 확인한다`() = runTest {
        withContext(Dispatchers.Default) {
            val schedule = 503L
            prepareSchedule(schedule)
            enter(client, schedule, 1)
            coroutineScope {
                val events = queueStream(second.client, schedule, 1).asFlow().produceIn(this)
                next(events, "position")
                assertThat(admission.admit(schedule)).isEqualTo(1)
                val token = redis.opsForValue().get(QueueKeys.admitted(schedule, 1)).awaitSingle()
                assertThat(next(events, "admitted").data()).contains(token)
                memberClient(second.client, 1).get().uri(queuePath(schedule) + "/me").exchange().expectStatus().isOk.expectBody()
                    .jsonPath("$.admissionToken").isEqualTo(token)
                events.cancel()
            }
        }
    }

    @Test
    fun `좌석 변경은 양쪽에 같은 순서로 전달되고 다른 인스턴스에서 재생된다`() = runTest {
        withContext(Dispatchers.Default) {
            val schedule = 504L
            prepareSchedule(schedule)
            coroutineScope {
                val first = seatStream(client, schedule).asFlow().produceIn(this)
                val other = seatStream(second.client, schedule).asFlow().produceIn(this)
                next(first, "snapshot")
                next(other, "snapshot")
                val ids = listOf(add(schedule, "HELD"), add(schedule, "SOLD"))
                val firstEvents = ids.map { next(first, "seat") }
                val otherEvents = ids.map { next(other, "seat") }
                assertThat(firstEvents.map { it.id() }).containsExactlyElementsOf(ids)
                assertThat(otherEvents.map { it.id() }).containsExactlyElementsOf(ids)
                assertThat(otherEvents.map { it.data() }).containsExactlyElementsOf(firstEvents.map { it.data() })
                first.cancel()
                other.cancel()
                val missed = listOf(add(schedule, "AVAILABLE"), add(schedule, "HELD"))
                val replay = seatStream(second.client, schedule, ids.last()).asFlow().produceIn(this)
                val replayed = missed.map { nextNonHeartbeat(replay) }
                assertThat(replayed.map { it.event() }).containsExactly("seat", "seat")
                assertThat(replayed.map { it.id() }).containsExactlyElementsOf(missed)
                replay.cancel()
            }
        }
    }

    @Test
    fun `순번 스트림 연결을 끊어도 다른 인스턴스에서 같은 순번으로 재연결한다`() = runTest {
        withContext(Dispatchers.Default) {
            val schedule = 505L
            prepareSchedule(schedule)
            enter(client, schedule, 1)
            enter(client, schedule, 2)
            coroutineScope {
                val first = queueStream(client, schedule, 2).asFlow().produceIn(this)
                assertThat(next(first, "position").data()).contains("\"position\":2")
                first.cancel()
                val other = queueStream(second.client, schedule, 2).asFlow().produceIn(this)
                assertThat(next(other, "position").data()).contains("\"position\":2")
                assertThat(redis.opsForZSet().rank(QueueKeys.waiting(schedule), "2").awaitSingle()).isEqualTo(1L)
                other.cancel()
            }
        }
    }

    private fun memberClient(instance: WebTestClient, member: Long) =
        instance.mutate().defaultHeader("X-Member-Id", member.toString()).build()

    private fun enter(instance: WebTestClient, schedule: Long, member: Long) {
        memberClient(instance, member).post().uri(queuePath(schedule)).exchange().expectStatus().isOk
    }

    private fun queuePath(schedule: Long) = "/api/v1/queues/$schedule/entries"

    private fun queueStream(instance: WebTestClient, schedule: Long, member: Long) =
        memberClient(instance, member).get().uri("/api/v1/streams/queues/$schedule").exchange().expectStatus().isOk
            .returnResult(object : ParameterizedTypeReference<ServerSentEvent<String>>() {}).responseBody

    private fun seatStream(instance: WebTestClient, schedule: Long, lastId: String? = null) =
        memberClient(instance, 1).get().uri("/api/v1/streams/schedules/$schedule/seats")
            .apply { if (lastId != null) header("Last-Event-ID", lastId) }.exchange().expectStatus().isOk
            .returnResult(object : ParameterizedTypeReference<ServerSentEvent<String>>() {}).responseBody

    private suspend fun add(schedule: Long, status: String): String = redis.opsForStream<String, String>().add(
        StreamRecords.mapBacked<String, String, String>(
            mapOf("seatIds" to "1", "status" to status, "at" to "100", "holdExpiresAt" to "200"),
        ).withStreamKey(streamKey(schedule)),
    ).awaitSingle().value

    private suspend fun next(events: ReceiveChannel<ServerSentEvent<String>>, name: String): ServerSentEvent<String> = withTimeout(5000) {
        while (true) {
            val event = events.receive()
            if (event.event() == name) return@withTimeout event
        }
        error("unreachable")
    }

    private suspend fun nextNonHeartbeat(events: ReceiveChannel<ServerSentEvent<String>>): ServerSentEvent<String> = withTimeout(5000) {
        while (true) {
            val event = events.receive()
            if (event.event() != null) return@withTimeout event
        }
        error("unreachable")
    }
}
