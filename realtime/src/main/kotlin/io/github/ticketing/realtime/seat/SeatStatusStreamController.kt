package io.github.ticketing.realtime.seat

import io.github.ticketing.realtime.queue.QueueProperties
import io.github.ticketing.realtime.support.RealtimeException
import io.github.ticketing.realtime.support.memberId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.Limit
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.math.BigInteger
import java.time.Clock

@RestController
class SeatStatusStreamController(
    private val redis: ReactiveStringRedisTemplate,
    private val hub: SeatEventHub,
    private val clock: Clock,
    private val properties: QueueProperties,
) {
    @GetMapping("/api/v1/streams/schedules/{scheduleId}/seats", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun stream(
        @PathVariable scheduleId: Long,
        @RequestHeader("X-Member-Id", required = false) member: String?,
        @RequestHeader("Last-Event-ID", required = false) lastEventId: String?,
    ): Flow<ServerSentEvent<Any>> {
        memberId(member)
        if (!redis.hasKey(snapshotKey(scheduleId)).awaitSingle()) {
            throw RealtimeException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND")
        }
        return channelFlow {
            val buffered = Channel<SeatEvent>(Channel.UNLIMITED)
            val ready = CompletableDeferred<Unit>()
            val subscription = launch { hub.events(scheduleId, ready).collect { buffered.send(it) } }
            try {
                ready.await()
                val heartbeat = launch {
                    while (true) {
                        delay(properties.heartbeatInterval.toMillis())
                        send(ServerSentEvent.builder<Any>().comment("heartbeat").build())
                    }
                }
                try {
                    var lastSent = initial(scheduleId, lastEventId) { send(it) }
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
        }
    }

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
