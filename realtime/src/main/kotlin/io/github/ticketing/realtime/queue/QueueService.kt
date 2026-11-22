package io.github.ticketing.realtime.queue

import io.github.ticketing.realtime.support.RealtimeException
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.core.io.ClassPathResource
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
class QueueService(
    private val redis: ReactiveStringRedisTemplate,
    private val clock: Clock,
    private val meters: MeterRegistry,
    private val properties: QueueProperties,
) {
    suspend fun enter(scheduleId: Long, memberId: Long): QueueStatus {
        requireSchedule(scheduleId)
        val registration = register(scheduleId, memberId)
        registration.admitted?.let { return it }

        meters.counter("queue.registration", "result", if (registration.added) "new" else "existing").increment()
        redis.opsForSet().add(QueueKeys.ACTIVE, scheduleId.toString()).awaitSingle()
        return QueueStatus.Waiting(registration.position + 1)
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
        val removed = redis.execute(
            leaveScript,
            listOf(QueueKeys.waiting(scheduleId), QueueKeys.seen(scheduleId)),
            listOf(memberId.toString()),
        ).next().awaitSingle()
        meters.counter("queue.departure", "reason", "explicit").increment(removed.toDouble())
    }

    private suspend fun requireSchedule(scheduleId: Long) {
        if (!redis.hasKey(QueueKeys.seats(scheduleId)).awaitSingle()) {
            throw RealtimeException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND")
        }
    }

    /** 입장 기록이 Gateway 키 기록 실패로 정리됐다면 한 번만 정상 등록으로 다시 진행한다. */
    private suspend fun register(scheduleId: Long, memberId: Long, firstAttempt: Boolean = true): Registration {
        val started = System.nanoTime()
        val result = try {
            redis.execute(
                registerScript,
                listOf(
                    QueueKeys.waiting(scheduleId),
                    QueueKeys.seen(scheduleId),
                    QueueKeys.sequence(scheduleId),
                    QueueKeys.admitted(scheduleId, memberId),
                ),
                listOf(memberId.toString(), clock.millis().toString()),
            ).next().awaitSingle()
        } finally {
            meters.timer("queue.presence.update").record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
        }
        val code = (result[0] as Number).toLong()
        if (code != ADMITTED_CODE) return Registration(added = code == 1L, position = (result[1] as Number).toLong(), admitted = null)

        if (firstAttempt) meters.counter("queue.registration", "result", "already_admitted").increment()
        val admitted = admittedStatus(scheduleId, memberId, result[2].toString())
        return when {
            admitted != null -> Registration(added = false, position = 0, admitted = admitted)
            firstAttempt -> register(scheduleId, memberId, firstAttempt = false)
            else -> throw RealtimeException(HttpStatus.NOT_FOUND, "QUEUE_ENTRY_NOT_FOUND")
        }
    }

    private suspend fun admitted(scheduleId: Long, memberId: Long): QueueStatus? {
        val token = redis.opsForValue().get(QueueKeys.admitted(scheduleId, memberId)).awaitSingleOrNull() ?: return null
        return admittedStatus(scheduleId, memberId, token)
    }

    /**
     * 입장 스크립트가 조회 키를 만든 뒤 Gateway 검증 키를 쓰기까지 짧은 공백이 있다(ADR 0021).
     * 조회 키가 방금 만들어졌다면 아직 준비 중이므로 지우지 않고 대기 중으로 응답한다. 토큰은 Gateway가 검증할 수 있을 때만 내보낸다.
     * 유예가 지나도 검증 키가 없으면 기록 실패나 만료이므로 조회 키를 정리하고 항목 없음으로 판정한다.
     */
    private suspend fun admittedStatus(scheduleId: Long, memberId: Long, token: String): QueueStatus? {
        val gatewayTtl = redis.getExpire(QueueKeys.admission(token)).awaitSingleOrNull()
        val key = QueueKeys.admitted(scheduleId, memberId)
        return when {
            gatewayTtl != null && gatewayTtl.isPositive -> QueueStatus.Admitted(token, clock.instant().plus(gatewayTtl))

            isRecentlyCreated(key) -> {
                meters.counter("queue.admission", "result", "preparing").increment()
                QueueStatus.Waiting(1)
            }

            else -> {
                redis.delete(key).awaitSingle()
                meters.counter("queue.admission", "result", "lookup_removed").increment()
                null
            }
        }
    }

    private suspend fun isRecentlyCreated(key: String): Boolean {
        val remaining = redis.getExpire(key).awaitSingleOrNull() ?: return false
        return remaining > properties.admissionTokenTtl.minus(properties.admissionPreparationGrace)
    }

    private suspend fun waiting(scheduleId: Long, memberId: Long): QueueStatus.Waiting? =
        redis.opsForZSet().rank(QueueKeys.waiting(scheduleId), memberId.toString()).awaitSingleOrNull()?.let {
            QueueStatus.Waiting(it + 1)
        }

    private suspend fun markSeen(scheduleId: Long, memberId: Long) {
        val started = System.nanoTime()
        try {
            redis.execute(
                touchSeenScript,
                listOf(QueueKeys.waiting(scheduleId), QueueKeys.seen(scheduleId)),
                listOf(memberId.toString(), clock.millis().toString()),
            ).next().awaitSingleOrNull()
        } finally {
            meters.timer("queue.presence.update").record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
        }
    }

    private class Registration(val added: Boolean, val position: Long, val admitted: QueueStatus?)

    companion object {
        private const val ADMITTED_CODE = 2L
        private val registerScript = DefaultRedisScript(loadQueueScript("register.lua"), List::class.java)
        private val leaveScript = DefaultRedisScript(loadQueueScript("leave.lua"), Long::class.java)
        private val touchSeenScript = DefaultRedisScript(loadQueueScript("touch-seen.lua"), Long::class.java)
    }
}

internal fun loadQueueScript(name: String): String =
    ClassPathResource("redis/queue/$name").inputStream.use { String(it.readBytes(), Charsets.UTF_8) }

/**
 * 대기열 확인을 먼저 하고, 없을 때만 입장 기록을 본다. 입장 스크립트가 대기열 제거와 입장 기록을 한 번에 처리하므로
 * 대기열에 없는 사용자는 이미 입장 기록이 있거나 대기열에서 빠진 것이어서 두 번째 조회가 필요 없다.
 */
internal suspend fun resolveQueueStatus(
    readWaiting: suspend () -> QueueStatus.Waiting?,
    readAdmitted: suspend () -> QueueStatus?,
): QueueStatus? = readWaiting() ?: readAdmitted()
