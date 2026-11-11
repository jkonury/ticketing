package io.github.ticketing.realtime.queue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID

interface AdmissionStore {
    suspend fun lock(scheduleId: Long, instanceId: String, duration: Duration): Boolean
    suspend fun admit(scheduleId: Long, cutoff: Long, ttl: Duration, tokens: List<String>): List<Pair<String, String>>
    suspend fun saveToken(scheduleId: Long, memberId: String, token: String, ttl: Duration)
    suspend fun removeActiveIfEmpty(scheduleId: Long)
    suspend fun activeSchedules(): List<String>
}

@Service
class AdmissionService(private val store: AdmissionStore, private val properties: QueueProperties, private val clock: Clock) {
    private val random = SecureRandom()
    private val instanceId = UUID.randomUUID().toString()

    suspend fun admit(scheduleId: Long): Int {
        if (!store.lock(scheduleId, instanceId, properties.admissionInterval)) return 0

        val tokens = List(properties.admissionBatchSize) { newToken() }
        val admitted = store.admit(scheduleId, clock.millis() - properties.presenceGrace.toMillis(), properties.admissionTokenTtl, tokens)
        admitted.forEach { (memberId, token) ->
            store.saveToken(scheduleId, memberId, token, properties.admissionTokenTtl)
        }
        store.removeActiveIfEmpty(scheduleId)
        return admitted.size
    }

    suspend fun admitAll() {
        val schedules = store.activeSchedules()
        for (schedule in schedules) {
            runCatching { admit(schedule.toLong()) }.onFailure { exception ->
                if (exception is CancellationException) throw exception
                logger.error("대기열 입장 처리 실패: scheduleId={}", schedule, exception)
            }
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val TOKEN_BYTES = 32
        private val logger = LoggerFactory.getLogger(AdmissionService::class.java)
    }
}

@Repository
class RedisAdmissionStore(private val redis: ReactiveStringRedisTemplate) : AdmissionStore {
    override suspend fun lock(scheduleId: Long, instanceId: String, duration: Duration): Boolean =
        redis.opsForValue().setIfAbsent("queue:admit-lock:{$scheduleId}", instanceId, duration).awaitSingle()

    override suspend fun admit(scheduleId: Long, cutoff: Long, ttl: Duration, tokens: List<String>): List<Pair<String, String>> {
        val keys = listOf(QueueKeys.waiting(scheduleId), QueueKeys.seen(scheduleId), "queue:admitted:{$scheduleId}:")
        val args = listOf(cutoff.toString(), ttl.toMillis().toString(), tokens.size.toString()) + tokens
        return redis.execute(admitScript, keys, args).next().awaitSingleOrNull().orEmpty().chunked(2).map { pair ->
            pair[0].toString() to pair[1].toString()
        }
    }

    override suspend fun saveToken(scheduleId: Long, memberId: String, token: String, ttl: Duration) {
        val key = QueueKeys.admission(token)
        redis.opsForHash<String, String>().putAll(key, mapOf("memberId" to memberId, "scheduleId" to scheduleId.toString())).awaitSingle()
        redis.expire(key, ttl).awaitSingle()
    }

    override suspend fun removeActiveIfEmpty(scheduleId: Long) {
        if (redis.opsForZSet().size(QueueKeys.waiting(scheduleId)).awaitSingle() == 0L) {
            redis.opsForSet().remove(QueueKeys.ACTIVE, scheduleId.toString()).awaitSingle()
        }
    }

    override suspend fun activeSchedules(): List<String> = redis.opsForSet().members(QueueKeys.ACTIVE).collectList().awaitSingle()

    companion object {
        private val admitScript = DefaultRedisScript(
            ClassPathResource("redis/queue/admit.lua").inputStream.use { String(it.readBytes(), Charsets.UTF_8) },
            List::class.java,
        )
    }
}

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "ticketing.realtime.queue", name = ["admission-enabled"], havingValue = "true", matchIfMissing = true)
class AdmissionScheduler(private val service: AdmissionService) {
    @Scheduled(fixedDelayString = "\${ticketing.realtime.queue.admission-interval:PT1S}")
    suspend fun admit() {
        service.admitAll()
    }
}
