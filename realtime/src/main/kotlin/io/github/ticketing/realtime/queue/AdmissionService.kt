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
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

@Service
class AdmissionService(private val redis: ReactiveStringRedisTemplate, private val properties: QueueProperties, private val clock: Clock) {
    private val random = SecureRandom()
    private val instanceId = UUID.randomUUID().toString()

    suspend fun admit(scheduleId: Long): Int {
        val lock = "queue:admit-lock:{$scheduleId}"
        if (!redis.opsForValue().setIfAbsent(lock, instanceId, properties.admissionInterval).awaitSingle()) return 0

        val tokens = List(properties.admissionBatchSize) { newToken() }
        val keys = listOf(QueueKeys.waiting(scheduleId), QueueKeys.seen(scheduleId), "queue:admitted:{$scheduleId}:")
        val args = listOf(
            (clock.millis() - properties.presenceGrace.toMillis()).toString(),
            properties.admissionTokenTtl.toMillis().toString(),
            properties.admissionBatchSize.toString(),
        ) + tokens
        val admitted = redis.execute(admitScript, keys, args).next().awaitSingleOrNull().orEmpty()
        admitted.chunked(2).forEach { pair ->
            val memberId = pair[0].toString()
            val token = pair[1].toString()
            val tokenKey = "queue:admission:$token"
            redis.opsForHash<String, String>().putAll(
                tokenKey,
                mapOf("memberId" to memberId, "scheduleId" to scheduleId.toString()),
            ).awaitSingle()
            redis.expire(tokenKey, properties.admissionTokenTtl).awaitSingle()
        }
        if (redis.opsForZSet().size(QueueKeys.waiting(scheduleId)).awaitSingle() == 0L) {
            redis.opsForSet().remove(QueueKeys.ACTIVE, scheduleId.toString()).awaitSingle()
        }
        return admitted.size / 2
    }

    suspend fun admitAll() {
        val schedules = redis.opsForSet().members(QueueKeys.ACTIVE).collectList().awaitSingle()
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
