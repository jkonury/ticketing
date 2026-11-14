package io.github.ticketing.realtime.queue

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Service
class AdmissionService(
    private val redis: ReactiveStringRedisTemplate,
    private val properties: QueueProperties,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val random = SecureRandom()
    private val instanceId = UUID.randomUUID().toString()

    suspend fun admit(scheduleId: Long): Int {
        val lock = "queue:admit-lock:{$scheduleId}"
        if (!redis.opsForValue().setIfAbsent(lock, instanceId, properties.admissionInterval).awaitSingle()) return 0

        val started = System.nanoTime()
        try {
            val tokens = List(properties.admissionBatchSize) { newToken() }
            val keys = listOf(QueueKeys.waiting(scheduleId), QueueKeys.seen(scheduleId), "queue:admitted:{$scheduleId}:")
            val args = listOf(
                (clock.millis() - properties.presenceGrace.toMillis()).toString(),
                properties.admissionTokenTtl.toMillis().toString(),
                properties.admissionBatchSize.toString(),
            ) + tokens
            val result = redis.execute(admitScript, keys, args).next().awaitSingleOrNull().orEmpty()
            val inspected = (result[0] as Number).toLong()
            val missing = (result[1] as Number).toLong()
            val expired = (result[2] as Number).toLong()
            val admitted = result[3] as List<*>
            meters.counter("queue.admission.candidates").increment(inspected.toDouble())
            meters.counter("queue.departure", "reason", "seen_missing").increment(missing.toDouble())
            meters.counter("queue.departure", "reason", "seen_expired").increment(expired.toDouble())
            meters.counter("queue.admission", "result", "lookup_created").increment((admitted.size / 2).toDouble())
            // 토큰마다 왕복을 기다리면 Redis 지연이 인원수만큼 곱해지므로 한꺼번에 보내 왕복을 겹친다.
            coroutineScope {
                admitted.chunked(2).map { pair ->
                    async {
                        val memberId = pair[0].toString()
                        val tokenKey = "queue:admission:${pair[1]}"
                        runCatching {
                            redis.opsForHash<String, String>().putAll(
                                tokenKey,
                                mapOf("memberId" to memberId, "scheduleId" to scheduleId.toString()),
                            ).awaitSingle()
                            redis.expire(tokenKey, properties.admissionTokenTtl).awaitSingle()
                            meters.counter("queue.admission", "result", "gateway_ready").increment()
                        }.onFailure {
                            meters.counter("queue.admission", "result", "gateway_write_failed").increment()
                        }.getOrThrow()
                    }
                }.awaitAll()
            }
            if (redis.opsForZSet().size(QueueKeys.waiting(scheduleId)).awaitSingle() == 0L) {
                redis.opsForSet().remove(QueueKeys.ACTIVE, scheduleId.toString()).awaitSingle()
                // 크기 확인과 제거 사이에 들어온 등록은 활성 회차를 이미 추가했더라도 방금 제거됐을 수 있다.
                // 등록은 대기열 추가 뒤 활성 회차를 추가하므로 제거 뒤 다시 확인하면 어느 순서든 놓치지 않는다.
                if (redis.opsForZSet().size(QueueKeys.waiting(scheduleId)).awaitSingle() > 0L) {
                    redis.opsForSet().add(QueueKeys.ACTIVE, scheduleId.toString()).awaitSingle()
                    meters.counter("queue.active", "result", "restored").increment()
                }
            }
            return admitted.size / 2
        } finally {
            meters.timer("queue.admission.batch").record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
        }
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
class AdmissionScheduler(private val service: AdmissionService, private val meters: MeterRegistry) {
    private val previousStart = AtomicLong()

    @Scheduled(fixedDelayString = "\${ticketing.realtime.queue.admission-interval:PT1S}")
    suspend fun admit() {
        val now = System.nanoTime()
        val previous = previousStart.getAndSet(now)
        if (previous != 0L) meters.timer("queue.admission.scheduler.interval").record(now - previous, TimeUnit.NANOSECONDS)
        withContext(Dispatchers.Default) { service.admitAll() }
    }
}
