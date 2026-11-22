package io.github.ticketing.realtime.seat

import io.github.ticketing.realtime.queue.QueueProperties
import io.github.ticketing.realtime.support.RealtimeException
import io.github.ticketing.realtime.support.memberId
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.Limit
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigInteger
import java.time.Clock

@RestController
class SeatStatusStreamController(
    private val redis: ReactiveStringRedisTemplate,
    private val hub: SeatEventHub,
    private val clock: Clock,
    private val properties: QueueProperties,
    private val meters: MeterRegistry,
) {
    @GetMapping("/api/v1/streams/schedules/{scheduleId}/seats", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun stream(
        @PathVariable scheduleId: Long,
        @RequestHeader("X-Member-Id", required = false) member: String?,
        @RequestHeader("Last-Event-ID", required = false) lastEventId: String?,
        @RequestParam("lastEventId", required = false) lastEventIdQuery: String?,
    ): Flow<ServerSentEvent<Any>> {
        memberId(member)
        if (!redis.hasKey(snapshotKey(scheduleId)).awaitSingle()) {
            throw RealtimeException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND")
        }
        return withoutOverflowError(
            channelFlow {
                val buffered = Channel<SeatEvent>(properties.seatStreamBuffer)
                val ready = CompletableDeferred<Unit>()
                val subscription = launch {
                    var overflowed = false
                    hub.events(scheduleId, ready).collectIntoBuffer(buffered) {
                        overflowed = true
                        meters.counter("seat.stream.overflow").increment()
                        logger.info("좌석 SSE 버퍼 상한을 넘어 연결을 종료: scheduleId={}, buffer={}", scheduleId, properties.seatStreamBuffer)
                    }
                    // 수신이 멈춘 연결은 버퍼를 비울 수 없으므로 producer scope를 실패시켜 초기 전송·send 대기까지 즉시 중단한다
                    if (overflowed) throw SeatStreamOverflowException()
                }
                try {
                    ready.await()
                    val heartbeat = launch {
                        while (true) {
                            delay(properties.heartbeatInterval.toMillis())
                            send(ServerSentEvent.builder<Any>().comment("heartbeat").build())
                        }
                    }
                    try {
                        var lastSent = initial(scheduleId, lastEventId?.takeIf { it.isNotBlank() } ?: lastEventIdQuery) { send(it) }
                        for (event in buffered) {
                            val id = requireNotNull(StreamId.parse(event.id))
                            if (id > lastSent) {
                                send(event.asSse())
                                lastSent = id
                            }
                        }
                    } finally {
                        heartbeat.cancelAndJoin()
                    }
                } finally {
                    subscription.cancelAndJoin()
                    buffered.close()
                }
            },
        )
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    private suspend fun initial(scheduleId: Long, requested: String?, send: suspend (ServerSentEvent<Any>) -> Unit): StreamId {
        val key = streamKey(scheduleId)
        val streams = redis.opsForStream<String, String>()
        val parsed = StreamId.parse(requested)
        val first = streams.range(key, Range.unbounded(), Limit.limit().count(1)).collectList().awaitSingle().firstOrNull()
        val exists = if (parsed != null && parsed != StreamId.ZERO) {
            streams.range(
                key,
                Range.closed(requireNotNull(requested), requireNotNull(requested)),
                Limit.limit().count(1),
            ).hasElements().awaitSingle()
        } else {
            parsed == StreamId.ZERO && (first == null || isCompleteStream(key))
        }
        val withinRange = first == null || parsed == StreamId.ZERO ||
            (parsed != null && parsed >= requireNotNull(StreamId.parse(first.id.value)))
        if (parsed != null && exists && withinRange) {
            var last = parsed
            streams.range(key, Range.from(Range.Bound.exclusive(requireNotNull(requested))).to(Range.Bound.unbounded())).asFlow().collect {
                val event = it.toSeatEvent()
                send(event.asSse())
                last = requireNotNull(StreamId.parse(event.id))
            }
            return last
        }
        val last =
            streams.reverseRange(key, Range.unbounded(), Limit.limit().count(1)).collectList().awaitSingle().firstOrNull()?.id?.value
                ?: "0-0"
        val seats = mutableListOf<SeatSnapshot>()
        redis.opsForHash<String, String>().entries(snapshotKey(scheduleId)).asFlow().collect { (id, value) ->
            seats.add(snapshot(id, value))
        }
        send(ServerSentEvent.builder<Any>(SnapshotData(seats.sortedBy { it.seatId })).id(last).event("snapshot").build())
        return requireNotNull(StreamId.parse(last))
    }

    private suspend fun isCompleteStream(key: String): Boolean {
        val info = redis.opsForStream<String, String>().info(key).awaitSingle()
        val added = info.raw["entries-added"] as? Number ?: return false
        return added.toLong() == info.streamLength()
    }

    private fun snapshot(id: String, value: String): SeatSnapshot {
        val expires = value.removePrefix("HELD:").toLongOrNull()
        return when {
            value.startsWith("HELD:") && expires != null && expires < clock.millis() -> SeatSnapshot(id.toLong(), "AVAILABLE")
            value.startsWith("HELD:") -> SeatSnapshot(id.toLong(), "HELD", expires)
            else -> SeatSnapshot(id.toLong(), value)
        }
    }
}

/**
 * hub 이벤트를 연결별 버퍼에 넣는다. 공유 SharedFlow 수집이 느린 연결 때문에 멈추면 같은 회차의 다른 구독자까지 막히므로
 * suspend하는 send 대신 trySend를 쓴다. 가득 차면 그 연결의 버퍼만 닫아 스트림을 정상 종료하고,
 * 클라이언트는 재접속한다(Last-Event-ID 재생 또는 스냅샷 재수신).
 */
// 초과로 끊긴 연결은 오류 응답 대신 정상 종료해 연결마다 오류 로그가 남지 않게 한다
private fun <T> withoutOverflowError(source: Flow<T>): Flow<T> = flow {
    try {
        emitAll(source)
    } catch (_: SeatStreamOverflowException) {
        // 초과는 subscription에서 이미 카운터와 로그로 기록했다
    }
}

internal class SeatStreamOverflowException : RuntimeException("좌석 SSE 버퍼 상한 초과", null, false, false)

internal suspend fun Flow<SeatEvent>.collectIntoBuffer(buffer: Channel<SeatEvent>, onOverflow: () -> Unit) {
    takeWhile { event ->
        val accepted = buffer.trySend(event).isSuccess
        if (!accepted) {
            onOverflow()
            buffer.close()
        }
        accepted
    }.collect()
}

private fun snapshotKey(scheduleId: Long) = "booking:seats:{$scheduleId}"
private fun SeatEvent.asSse(): ServerSentEvent<Any> = ServerSentEvent.builder<Any>(SeatData(seatIds, status, holdExpiresAt, at))
    .id(id).event("seat").build()

data class SeatSnapshot(val seatId: Long, val status: String, val holdExpiresAt: Long? = null)
data class SnapshotData(val seats: List<SeatSnapshot>)
data class SeatData(val seatIds: List<Long>, val status: String, val holdExpiresAt: Long?, val at: Long)

private data class StreamId(val milliseconds: BigInteger, val sequence: BigInteger) : Comparable<StreamId> {
    override fun compareTo(other: StreamId): Int = compareValuesBy(this, other, StreamId::milliseconds, StreamId::sequence)

    companion object {
        val ZERO = StreamId(BigInteger.ZERO, BigInteger.ZERO)
        fun parse(value: String?): StreamId? {
            if (value == null || !value.matches(Regex("[0-9]+-[0-9]+"))) return null
            val parts = value.split('-')
            val milliseconds = parts[0].toBigInteger()
            val sequence = parts[1].toBigInteger()
            val max = BigInteger("18446744073709551615")
            return if (milliseconds > max || sequence > max) null else StreamId(milliseconds, sequence)
        }
    }
}
