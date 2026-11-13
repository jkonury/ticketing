package io.github.ticketing.realtime.queue

import io.github.ticketing.realtime.support.RealtimeException
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit

sealed interface QueueStatus {
    data class Waiting(val position: Long) : QueueStatus

    data class Admitted(val admissionToken: String, val expiresAt: Instant) : QueueStatus
}

object QueueKeys {
    fun waiting(scheduleId: Long) = "queue:waiting:{$scheduleId}"
    fun seen(scheduleId: Long) = "queue:seen:{$scheduleId}"
    fun sequence(scheduleId: Long) = "queue:seq:{$scheduleId}"
    fun admitted(scheduleId: Long, memberId: Long) = "queue:admitted:{$scheduleId}:$memberId"
    fun admission(token: String) = "queue:admission:$token"
    fun seats(scheduleId: Long) = "booking:seats:{$scheduleId}"
    const val ACTIVE = "queue:active"
}

@Service
class QueueService(private val redis: ReactiveStringRedisTemplate, private val clock: Clock, private val meters: MeterRegistry) {
    suspend fun enter(scheduleId: Long, memberId: Long): QueueStatus {
        requireSchedule(scheduleId)
        admitted(scheduleId, memberId)?.let {
            meters.counter("queue.registration", "result", "already_admitted").increment()
            return it
        }
        val sequence = redis.opsForValue().increment(QueueKeys.sequence(scheduleId)).awaitSingle()
        val added = redis.execute(
            addWaiting,
            listOf(QueueKeys.waiting(scheduleId)),
            sequence.toString(),
            memberId.toString(),
        ).next().awaitSingle()
        meters.counter("queue.registration", "result", if (added == 1L) "new" else "existing").increment()
        redis.opsForSet().add(QueueKeys.ACTIVE, scheduleId.toString()).awaitSingle()
        markSeen(scheduleId, memberId)
        return waiting(scheduleId, memberId) ?: throw RealtimeException(HttpStatus.NOT_FOUND, "QUEUE_ENTRY_NOT_FOUND")
    }

    /**
     * 대기 중이면 `ZRANK` 한 번으로 끝낸다. SSE 폴링이 연결 수만큼 초당 호출하므로 명령 수가 곧 부하다.
     * [verifySchedule]을 끄면 회차 존재 확인(`EXISTS`)도 생략한다. 진입 시점과 하트비트에서만 확인한다.
     */
    suspend fun status(scheduleId: Long, memberId: Long, updateSeen: Boolean = true, verifySchedule: Boolean = true): QueueStatus {
        if (verifySchedule) requireSchedule(scheduleId)
        val status = resolveQueueStatus(
            readWaiting = { waiting(scheduleId, memberId) },
            readAdmitted = { admitted(scheduleId, memberId) },
        ) ?: throw RealtimeException(HttpStatus.NOT_FOUND, "QUEUE_ENTRY_NOT_FOUND")
        if (status is QueueStatus.Waiting && updateSeen) markSeen(scheduleId, memberId)
        return status
    }

    suspend fun leave(scheduleId: Long, memberId: Long) {
        val removed = redis.opsForZSet().remove(QueueKeys.waiting(scheduleId), memberId.toString()).awaitSingle()
        meters.counter("queue.departure", "reason", "explicit").increment(removed.toDouble())
        redis.opsForZSet().remove(QueueKeys.seen(scheduleId), memberId.toString()).awaitSingle()
    }

    private suspend fun requireSchedule(scheduleId: Long) {
        if (!redis.hasKey(QueueKeys.seats(scheduleId)).awaitSingle()) {
            throw RealtimeException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND")
        }
    }

    private suspend fun admitted(scheduleId: Long, memberId: Long): QueueStatus.Admitted? {
        val key = QueueKeys.admitted(scheduleId, memberId)
        val token = redis.opsForValue().get(key).awaitSingleOrNull() ?: return null
        val tokenKey = QueueKeys.admission(token)
        val ttl = redis.getExpire(tokenKey).awaitSingleOrNull()
        return if (ttl == null || ttl.isNegative || ttl.isZero) {
            redis.delete(key).awaitSingle()
            null
        } else {
            QueueStatus.Admitted(token, clock.instant().plus(ttl))
        }
    }

    private suspend fun waiting(scheduleId: Long, memberId: Long): QueueStatus.Waiting? =
        redis.opsForZSet().rank(QueueKeys.waiting(scheduleId), memberId.toString()).awaitSingleOrNull()?.let {
            QueueStatus.Waiting(it + 1)
        }

    private suspend fun markSeen(scheduleId: Long, memberId: Long) {
        val started = System.nanoTime()
        try {
            redis.opsForZSet().add(QueueKeys.seen(scheduleId), memberId.toString(), clock.millis().toDouble()).awaitSingle()
        } finally {
            meters.timer("queue.presence.update").record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
        }
    }

    companion object {
        private val addWaiting = DefaultRedisScript("return redis.call('ZADD', KEYS[1], 'NX', ARGV[1], ARGV[2])", Long::class.java)
    }
}

/**
 * 대기열 확인을 먼저 하고, 없을 때만 입장 기록을 본다. 입장 스크립트가 대기열 제거와 입장 기록을 한 번에 처리하므로
 * 대기열에 없는 사용자는 이미 입장 기록이 있거나 대기열에서 빠진 것이어서 두 번째 조회가 필요 없다.
 */
internal suspend fun resolveQueueStatus(
    readWaiting: suspend () -> QueueStatus.Waiting?,
    readAdmitted: suspend () -> QueueStatus.Admitted?,
): QueueStatus? = readWaiting() ?: readAdmitted()
