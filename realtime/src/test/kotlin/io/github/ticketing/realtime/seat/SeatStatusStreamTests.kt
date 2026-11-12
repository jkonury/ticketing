package io.github.ticketing.realtime.seat

import io.github.ticketing.realtime.RealtimeIntegrationTestSupport
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.ParameterizedTypeReference
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

@TestPropertySource(properties = ["ticketing.realtime.queue.heartbeat-interval=100ms"])
class SeatStatusStreamTests(client: WebTestClient, redis: ReactiveStringRedisTemplate, private val hub: SeatEventHub) :
    RealtimeIntegrationTestSupport(client, redis) {
    @Test
    fun `스냅샷과 두 구독자의 실시간 좌석 변경 및 heartbeat를 보낸다`() = runTest {
        withContext(Dispatchers.Default) {
            val schedule = 401L
            prepareSchedule(schedule)
            redis.opsForHash<String, String>().putAll(
                "booking:seats:{$schedule}",
                mapOf(
                    "2" to "HELD:${now.toEpochMilli() - 1}",
                    "3" to "HELD:${now.toEpochMilli() + 10000}",
                ),
            ).awaitSingle()
            val initial = add(schedule, "SOLD")
            coroutineScope {
                val first = stream(schedule).asFlow().produceIn(this)
                val snapshot = next(first, "snapshot")
                assertThat(snapshot.id()).isEqualTo(initial)
                assertThat(snapshot.data()).contains("\"seatId\":2,\"status\":\"AVAILABLE\"")
                    .contains("\"seatId\":3,\"status\":\"HELD\",\"holdExpiresAt\":${now.toEpochMilli() + 10000}")
                val second = stream(schedule).asFlow().produceIn(this)
                next(second, "snapshot")
                val changed = add(schedule, "HELD")
                assertThat(next(first, "seat").id()).isEqualTo(changed)
                assertThat(next(second, "seat").id()).isEqualTo(changed)
                withTimeout(5000) { while (first.receive().comment() != "heartbeat") Unit }
                first.cancel()
                second.cancel()
            }
        }
    }

    @Test
    fun `마지막 이벤트 이후를 재생하고 잘린 ID와 잘못된 ID는 스냅샷을 보낸다`() = runTest {
        withContext(Dispatchers.Default) {
            val schedule = 402L
            prepareSchedule(schedule)
            val old = add(schedule, "SOLD")
            val middle = add(schedule, "AVAILABLE")
            val latest = add(schedule, "HELD")
            coroutineScope {
                val replay = stream(schedule, old).asFlow().produceIn(this)
                assertThat(next(replay, "seat").id()).isEqualTo(middle)
                assertThat(next(replay, "seat").id()).isEqualTo(latest)
                val live = add(schedule, "SOLD")
                assertThat(next(replay, "seat").id()).isEqualTo(live)
                replay.cancel()
                redis.opsForStream<String, String>().delete(
                    streamKey(schedule),
                    org.springframework.data.redis.connection.stream.RecordId.of(old),
                ).awaitSingle()
                val trimmed = stream(schedule, old).asFlow().produceIn(this)
                assertThat(next(trimmed, "snapshot").id()).isEqualTo(live)
                trimmed.cancel()
                val invalid = stream(schedule, "invalid").asFlow().produceIn(this)
                assertThat(next(invalid, "snapshot").id()).isEqualTo(live)
                invalid.cancel()
            }
        }
    }

    @Test
    fun `쿼리 ID 이후를 재생하고 헤더가 있으면 헤더를 우선한다`() = runTest {
        withContext(Dispatchers.Default) {
            val schedule = 408L
            prepareSchedule(schedule)
            val first = add(schedule, "SOLD")
            val second = add(schedule, "AVAILABLE")
            val third = add(schedule, "HELD")
            coroutineScope {
                val queryReplay = stream(schedule, queryId = first).asFlow().produceIn(this)
                val replayedFromQuery = nextData(queryReplay)
                assertThat(replayedFromQuery.event()).isEqualTo("seat")
                assertThat(replayedFromQuery.id()).isEqualTo(second)
                assertThat(nextData(queryReplay).id()).isEqualTo(third)
                queryReplay.cancel()

                val headerReplay = stream(schedule, first, second).asFlow().produceIn(this)
                val replayed = nextData(headerReplay)
                assertThat(replayed.event()).isEqualTo("seat")
                assertThat(replayed.id()).isEqualTo(second)
                headerReplay.cancel()

                val invalid = stream(schedule, queryId = "invalid").asFlow().produceIn(this)
                assertThat(nextData(invalid).event()).isEqualTo("snapshot")
                invalid.cancel()
            }
        }
    }

    @Test
    fun `빈 스트림 스냅샷 이후 연속 변경은 순서대로 한 번씩 전달된다`() = runTest {
        withContext(Dispatchers.Default) {
            val schedule = 406L
            prepareSchedule(schedule)
            coroutineScope {
                val events = stream(schedule).asFlow().produceIn(this)
                assertThat(next(events, "snapshot").id()).isEqualTo("0-0")
                val ids = (1..20).map { add(schedule, "SOLD") }
                val delivered = ids.map { next(events, "seat").id() }
                assertThat(delivered).containsExactlyElementsOf(ids)
                assertThat(withTimeoutOrNull(300) { next(events, "seat") }).isNull()
                events.cancel()
            }
        }
    }

    @Test
    fun `처음부터 남은 스트림은 0부터 재생하고 잘린 스트림은 스냅샷을 보낸다`() = runTest {
        withContext(Dispatchers.Default) {
            val schedule = 407L
            prepareSchedule(schedule)
            val first = add(schedule, "SOLD")
            val second = add(schedule, "AVAILABLE")
            coroutineScope {
                val replay = stream(schedule, "0-0").asFlow().produceIn(this)
                assertThat(next(replay, "seat").id()).isEqualTo(first)
                assertThat(next(replay, "seat").id()).isEqualTo(second)
                replay.cancel()
                redis.opsForStream<String, String>().delete(
                    streamKey(schedule),
                    org.springframework.data.redis.connection.stream.RecordId.of(first),
                ).awaitSingle()
                val trimmed = stream(schedule, "0-0").asFlow().produceIn(this)
                assertThat(next(trimmed, "snapshot").id()).isEqualTo(second)
                trimmed.cancel()
            }
        }
    }

    @Test
    fun `마지막 구독이 끝나면 재구독은 새 리더로 시작한다`() = runTest {
        withContext(Dispatchers.Default) {
            val schedule = 405L
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val first = hub.events(schedule, ready).produceIn(this)
                ready.await()
                val event = add(schedule, "SOLD")
                assertThat(withTimeout(5000) { first.receive() }.id).isEqualTo(event)
                first.cancel()
            }
            val between = add(schedule, "AVAILABLE")
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val second = hub.events(schedule, ready).produceIn(this)
                ready.await()
                assertThat(withTimeoutOrNull(300) { second.receive() }).isNull()
                val live = add(schedule, "HELD")
                assertThat(withTimeout(5000) { second.receive() }.id).isEqualTo(live).isNotEqualTo(between)
                second.cancel()
            }
        }
    }

    @Test
    fun `회원 헤더 누락과 없는 회차는 스트림 시작 전 오류다`() = runTest {
        client.get().uri("/api/v1/streams/schedules/403/seats").exchange().expectStatus().isUnauthorized.expectBody()
            .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
        clientFor(1).get().uri("/api/v1/streams/schedules/404/seats").exchange().expectStatus().isNotFound.expectBody()
            .jsonPath("$.code").isEqualTo("SCHEDULE_NOT_FOUND")
    }

    private suspend fun add(schedule: Long, status: String): String = redis.opsForStream<String, String>().add(
        StreamRecords.mapBacked<String, String, String>(
            mapOf(
                "seatIds" to "1,2",
                "status" to status,
                "at" to "100",
                "holdExpiresAt" to "200",
            ),
        )
            .withStreamKey(streamKey(schedule)),
    ).awaitSingle().value

    private fun stream(schedule: Long, lastId: String? = null, queryId: String? = null) = clientFor(1).get().uri { builder ->
        builder.path("/api/v1/streams/schedules/$schedule/seats")
            .apply { if (queryId != null) queryParam("lastEventId", queryId) }.build()
    }
        .apply { if (lastId != null) header("Last-Event-ID", lastId) }.exchange().expectStatus().isOk
        .returnResult(object : ParameterizedTypeReference<ServerSentEvent<String>>() {}).responseBody

    private suspend fun nextData(events: ReceiveChannel<ServerSentEvent<String>>): ServerSentEvent<String> = withTimeout(5000) {
        while (true) {
            val event = events.receive()
            if (event.event() != null) return@withTimeout event
        }
        error("unreachable")
    }

    private suspend fun next(events: ReceiveChannel<ServerSentEvent<String>>, name: String): ServerSentEvent<String> = withTimeout(5000) {
        while (true) {
            val event = events.receive()
            if (event.event() == name) return@withTimeout event
        }
        error("unreachable")
    }
}
