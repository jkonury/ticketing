package io.github.ticketing.realtime.queue

import io.github.ticketing.realtime.support.RealtimeException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
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

interface QueueStore {
    suspend fun scheduleExists(scheduleId: Long): Boolean
    suspend fun admissionToken(scheduleId: Long, memberId: Long): String?
    suspend fun tokenTtl(token: String): Duration?
    suspend fun removeAdmission(scheduleId: Long, memberId: Long)
    suspend fun addWaiting(scheduleId: Long, memberId: Long)
    suspend fun position(scheduleId: Long, memberId: Long): Long?
    suspend fun markSeen(scheduleId: Long, memberId: Long, time: Long)
    suspend fun leave(scheduleId: Long, memberId: Long)
}

@Service
class QueueService(private val store: QueueStore, private val clock: Clock) {
    suspend fun enter(scheduleId: Long, memberId: Long): QueueStatus {
        requireSchedule(scheduleId)
        admitted(scheduleId, memberId)?.let { return it }
        store.addWaiting(scheduleId, memberId)
        markSeen(scheduleId, memberId)
        return waiting(scheduleId, memberId) ?: throw RealtimeException(HttpStatus.NOT_FOUND, "QUEUE_ENTRY_NOT_FOUND")
    }

    suspend fun status(scheduleId: Long, memberId: Long, updateSeen: Boolean = true): QueueStatus {
        requireSchedule(scheduleId)
        admitted(scheduleId, memberId)?.let { return it }
        val waiting = waiting(scheduleId, memberId) ?: throw RealtimeException(HttpStatus.NOT_FOUND, "QUEUE_ENTRY_NOT_FOUND")
        if (updateSeen) markSeen(scheduleId, memberId)
        return waiting
    }

    suspend fun leave(scheduleId: Long, memberId: Long) {
        store.leave(scheduleId, memberId)
    }

    private suspend fun requireSchedule(scheduleId: Long) {
        if (!store.scheduleExists(scheduleId)) {
            throw RealtimeException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND")
        }
    }

    private suspend fun admitted(scheduleId: Long, memberId: Long): QueueStatus.Admitted? {
        val token = store.admissionToken(scheduleId, memberId) ?: return null
        val ttl = store.tokenTtl(token)
        return if (ttl == null || ttl.isNegative || ttl.isZero) {
            store.removeAdmission(scheduleId, memberId)
            null
        } else {
            QueueStatus.Admitted(token, clock.instant().plus(ttl))
        }
    }

    private suspend fun waiting(scheduleId: Long, memberId: Long): QueueStatus.Waiting? =
        store.position(scheduleId, memberId)?.let { QueueStatus.Waiting(it + 1) }

    private suspend fun markSeen(scheduleId: Long, memberId: Long) {
        store.markSeen(scheduleId, memberId, clock.millis())
    }
}

@Repository
class RedisQueueStore(private val redis: ReactiveStringRedisTemplate) : QueueStore {
    override suspend fun scheduleExists(scheduleId: Long): Boolean = redis.hasKey(QueueKeys.seats(scheduleId)).awaitSingle()

    override suspend fun admissionToken(scheduleId: Long, memberId: Long): String? =
        redis.opsForValue().get(QueueKeys.admitted(scheduleId, memberId)).awaitSingleOrNull()

    override suspend fun tokenTtl(token: String): Duration? = redis.getExpire(QueueKeys.admission(token)).awaitSingleOrNull()

    override suspend fun removeAdmission(scheduleId: Long, memberId: Long) {
        redis.delete(QueueKeys.admitted(scheduleId, memberId)).awaitSingle()
    }

    override suspend fun addWaiting(scheduleId: Long, memberId: Long) {
        val sequence = redis.opsForValue().increment(QueueKeys.sequence(scheduleId)).awaitSingle()
        redis.execute(addWaiting, listOf(QueueKeys.waiting(scheduleId)), sequence.toString(), memberId.toString()).next().awaitSingle()
        redis.opsForSet().add(QueueKeys.ACTIVE, scheduleId.toString()).awaitSingle()
    }

    override suspend fun position(scheduleId: Long, memberId: Long): Long? =
        redis.opsForZSet().rank(QueueKeys.waiting(scheduleId), memberId.toString()).awaitSingleOrNull()

    override suspend fun markSeen(scheduleId: Long, memberId: Long, time: Long) {
        redis.opsForZSet().add(QueueKeys.seen(scheduleId), memberId.toString(), time.toDouble()).awaitSingle()
    }

    override suspend fun leave(scheduleId: Long, memberId: Long) {
        redis.opsForZSet().remove(QueueKeys.waiting(scheduleId), memberId.toString()).awaitSingle()
        redis.opsForZSet().remove(QueueKeys.seen(scheduleId), memberId.toString()).awaitSingle()
    }

    companion object {
        private val addWaiting = DefaultRedisScript("return redis.call('ZADD', KEYS[1], 'NX', ARGV[1], ARGV[2])", Long::class.java)
    }
}
