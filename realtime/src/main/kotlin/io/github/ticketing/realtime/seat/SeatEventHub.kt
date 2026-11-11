package io.github.ticketing.realtime.seat

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.Limit
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component

private const val POLL_MILLIS = 100L

data class SeatEvent(val id: String, val seatIds: List<Long>, val status: String, val holdExpiresAt: Long?, val at: Long)

private sealed interface HubMessage {
    data object Ready : HubMessage
    data class Event(val value: SeatEvent) : HubMessage
}

/** 비블로킹 XREAD를 주기적으로 실행해 공유 reactive Redis 연결을 점유하지 않는다.
 * StreamReceiver는 폴링을 사용하고 latest 오프셋에서 누락이 가능하므로 명시적인 마지막 ID를 유지한다. */
@Component
class SeatEventHub(private val redis: ReactiveStringRedisTemplate) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val entries = mutableMapOf<Long, Entry>()

    private inner class Entry(scheduleId: Long) {
        val job = SupervisorJob(scope.coroutineContext[Job])
        val shared = reader(scheduleId).shareIn(CoroutineScope(scope.coroutineContext + job), SharingStarted.WhileSubscribed(), replay = 1)
        var subscribers = 0
    }

    fun events(scheduleId: Long): Flow<SeatEvent> = events(scheduleId, CompletableDeferred())

    internal fun events(scheduleId: Long, ready: CompletableDeferred<Unit>): Flow<SeatEvent> = flow {
        val entry = synchronized(entries) {
            entries.getOrPut(scheduleId) { Entry(scheduleId) }.also { it.subscribers++ }
        }
        try {
            entry.shared.collect { message ->
                ready.complete(Unit)
                if (message is HubMessage.Event) emit(message.value)
            }
        } finally {
            synchronized(entries) {
                entry.subscribers--
                if (entry.subscribers == 0) {
                    entries.remove(scheduleId, entry)
                    entry.job.cancel()
                }
            }
        }
    }

    private fun reader(scheduleId: Long): Flow<HubMessage> = flow {
        val key = streamKey(scheduleId)
        var lastId: String? = null
        while (true) {
            try {
                if (lastId == null) {
                    lastId = redis.opsForStream<String, String>().reverseRange(key, Range.unbounded(), Limit.limit().count(1))
                        .asFlow().let { records ->
                            var id = "0-0"
                            records.collect { id = it.id.value }
                            id
                        }
                    emit(HubMessage.Ready)
                }
                redis.opsForStream<String, String>().read(StreamOffset.create(key, ReadOffset.from(lastId)))
                    .asFlow().collect { record ->
                        emit(HubMessage.Event(record.toSeatEvent()))
                        lastId = record.id.value
                    }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: DataAccessException) {
                logger.warn("좌석 이벤트 읽기 실패: scheduleId={}", scheduleId, exception)
            }
            delay(POLL_MILLIS)
        }
    }

    @PreDestroy
    fun close() = scope.cancel()
}

internal fun streamKey(scheduleId: Long) = "booking:seat-events:{$scheduleId}"

internal fun MapRecord<String, String, String>.toSeatEvent(): SeatEvent = SeatEvent(
    id.value,
    value.getValue("seatIds").split(',').map(String::toLong),
    value.getValue("status"),
    value["holdExpiresAt"]?.toLong(),
    value.getValue("at").toLong(),
)
