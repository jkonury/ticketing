package io.github.ticketing.realtime.queue

import io.github.ticketing.realtime.support.RealtimeException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

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
class QueueService(private val redis: ReactiveStringRedisTemplate, private val clock: Clock) {
    suspend fun enter(scheduleId: Long, memberId: Long): QueueStatus {
        requireSchedule(scheduleId)
        admitted(scheduleId, memberId)?.let { return it }
        val sequence = redis.opsForValue().increment(QueueKeys.sequence(scheduleId)).awaitSingle()
        redis.execute(addWaiting, listOf(QueueKeys.waiting(scheduleId)), sequence.toString(), memberId.toString()).next().awaitSingle()
        redis.opsForSet().add(QueueKeys.ACTIVE, scheduleId.toString()).awaitSingle()
        markSeen(scheduleId, memberId)
        return waiting(scheduleId, memberId) ?: throw RealtimeException(HttpStatus.NOT_FOUND, "QUEUE_ENTRY_NOT_FOUND")
    }

    suspend fun status(scheduleId: Long, memberId: Long): QueueStatus {
        requireSchedule(scheduleId)
        admitted(scheduleId, memberId)?.let { return it }
        val waiting = waiting(scheduleId, memberId) ?: throw RealtimeException(HttpStatus.NOT_FOUND, "QUEUE_ENTRY_NOT_FOUND")
        markSeen(scheduleId, memberId)
        return waiting
    }

    suspend fun leave(scheduleId: Long, memberId: Long) {
        redis.opsForZSet().remove(QueueKeys.waiting(scheduleId), memberId.toString()).awaitSingle()
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
        redis.opsForZSet().add(QueueKeys.seen(scheduleId), memberId.toString(), clock.millis().toDouble()).awaitSingle()
    }

    companion object {
        private val addWaiting = DefaultRedisScript("return redis.call('ZADD', KEYS[1], 'NX', ARGV[1], ARGV[2])", Long::class.java)
    }
}
