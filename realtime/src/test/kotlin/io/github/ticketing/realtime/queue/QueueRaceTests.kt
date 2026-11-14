package io.github.ticketing.realtime.queue

import io.github.ticketing.realtime.MutableClock
import io.github.ticketing.realtime.RealtimeIntegrationTestSupport
import io.github.ticketing.realtime.support.RealtimeException
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.ParameterizedTypeReference
import org.springframework.data.redis.core.ReactiveSetOperations
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveZSetOperations
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * 실행 순서를 코드로 통제해 대기열 경합을 재현한다. [InterleavingRedis]의 훅은 서비스가 Redis 명령을 보내기 직전에
 * 한 번만 실행되며, 훅 안에서 다른 흐름(입장 처리, 새 등록)을 완료시켜 "그 사이에 끼어든" 상태를 만든다.
 */
@TestPropertySource(
    properties = [
        "ticketing.realtime.queue.admission-batch-size=2",
        "ticketing.realtime.queue.position-poll-interval=50ms",
        "ticketing.realtime.queue.heartbeat-interval=500ms",
    ],
)
class QueueRaceTests(
    client: WebTestClient,
    redis: ReactiveStringRedisTemplate,
    private val queue: QueueService,
    private val admission: AdmissionService,
    private val properties: QueueProperties,
    private val clock: MutableClock,
    private val meters: MeterRegistry,
) : RealtimeIntegrationTestSupport(client, redis) {
    @Test
    fun `등록 직후 입장 처리는 생존 신호 없음으로 제거하지 않고 입장시킨다`() = runTest {
        val schedule = 601L
        prepareSchedule(schedule)
        val missingBefore = meters.counter("queue.departure", "reason", "seen_missing").count()

        queue.enter(schedule, 1)
        assertThat(redis.opsForZSet().score(QueueKeys.seen(schedule), "1").awaitSingle()).isNotNull()
        assertThat(admission.admit(schedule)).isEqualTo(1)

        assertThat(meters.counter("queue.departure", "reason", "seen_missing").count() - missingBefore).isZero()
        assertThat(queue.status(schedule, 1)).isInstanceOf(QueueStatus.Admitted::class.java)
    }

    @Test
    fun `재등록 중 입장 처리가 끼어들면 대기열에 다시 넣지 않고 입장 상태를 반환한다`() = runTest {
        val schedule = 602L
        prepareSchedule(schedule)
        val interleaving = InterleavingRedis(redis)
        val racing = QueueService(interleaving, clock, meters, properties)
        racing.enter(schedule, 1)
        interleaving.beforeRegister = { assertThat(admission.admit(schedule)).isEqualTo(1) }

        val response = racing.enter(schedule, 1)

        val token = redis.opsForValue().get(QueueKeys.admitted(schedule, 1)).awaitSingle()
        assertThat(response).isInstanceOfSatisfying(QueueStatus.Admitted::class.java) { assertThat(it.admissionToken).isEqualTo(token) }
        assertThat(redis.opsForZSet().size(QueueKeys.waiting(schedule)).awaitSingle()).isZero()
        assertThat(redis.opsForZSet().size(QueueKeys.seen(schedule)).awaitSingle()).isZero()
        assertThat(admission.admit(schedule)).isZero()
        assertThat(redis.opsForValue().get(QueueKeys.admitted(schedule, 1)).awaitSingle()).isEqualTo(token)
    }

    @Test
    fun `순번 조회 뒤 입장 처리가 끼어들어도 다음 조회는 입장 토큰을 주고 생존 신호가 남지 않는다`() = runTest {
        val schedule = 603L
        prepareSchedule(schedule)
        val interleaving = InterleavingRedis(redis)
        val racing = QueueService(interleaving, clock, meters, properties)
        racing.enter(schedule, 1)
        interleaving.beforeSeenUpdate = { assertThat(admission.admit(schedule)).isEqualTo(1) }

        val polled = racing.status(schedule, 1)
        val next = racing.status(schedule, 1)

        assertThat(polled).isEqualTo(QueueStatus.Waiting(1))
        assertThat(next).isInstanceOf(QueueStatus.Admitted::class.java)
        assertThat(redis.opsForZSet().size(QueueKeys.seen(schedule)).awaitSingle()).isZero()
        assertThat(redis.opsForZSet().size(QueueKeys.waiting(schedule)).awaitSingle()).isZero()
    }

    @Test
    fun `Gateway 키 기록 전의 조회 키는 지우지 않고 대기 중으로 응답한다`() = runTest {
        val schedule = 604L
        prepareSchedule(schedule)
        redis.opsForValue().set(QueueKeys.admitted(schedule, 1), "token-604", properties.admissionTokenTtl).awaitSingle()

        assertThat(queue.status(schedule, 1)).isEqualTo(QueueStatus.Waiting(1))
        assertThat(queue.enter(schedule, 1)).isEqualTo(QueueStatus.Waiting(1))
        assertThat(redis.hasKey(QueueKeys.admitted(schedule, 1)).awaitSingle()).isTrue()
        assertThat(redis.opsForZSet().size(QueueKeys.waiting(schedule)).awaitSingle()).isZero()

        // 해시는 생겼지만 만료 시간이 아직 없는 사이도 준비 중이다.
        redis.opsForHash<String, String>().put(QueueKeys.admission("token-604"), "memberId", "1").awaitSingle()
        assertThat(queue.status(schedule, 1)).isEqualTo(QueueStatus.Waiting(1))
        assertThat(redis.hasKey(QueueKeys.admitted(schedule, 1)).awaitSingle()).isTrue()

        redis.expire(QueueKeys.admission("token-604"), properties.admissionTokenTtl).awaitSingle()
        assertThat(queue.status(schedule, 1)).isInstanceOf(QueueStatus.Admitted::class.java)
    }

    @Test
    fun `준비 유예가 지나도 Gateway 키가 없으면 조회 키를 정리하고 항목 없음으로 판정한다`() = runTest {
        val schedule = 605L
        prepareSchedule(schedule)
        val aged = properties.admissionTokenTtl.minus(properties.admissionPreparationGrace).minusSeconds(30)
        redis.opsForValue().set(QueueKeys.admitted(schedule, 1), "token-605", aged).awaitSingle()

        assertThatThrownBy { kotlinx.coroutines.runBlocking { queue.status(schedule, 1) } }
            .isInstanceOfSatisfying(RealtimeException::class.java) { assertThat(it.code).isEqualTo("QUEUE_ENTRY_NOT_FOUND") }
        assertThat(redis.hasKey(QueueKeys.admitted(schedule, 1)).awaitSingle()).isFalse()
    }

    @Test
    fun `준비 중인 조회 키가 있어도 SSE는 removed를 보내지 않고 준비가 끝나면 admitted를 보낸다`() = runTest {
        withContext(Dispatchers.Default) {
            val schedule = 606L
            prepareSchedule(schedule)
            redis.opsForValue().set(QueueKeys.admitted(schedule, 1), "token-606", properties.admissionTokenTtl).awaitSingle()
            coroutineScope {
                val events = stream(schedule, 1).asFlow().produceIn(this)
                val first = nextNamed(events)
                assertThat(first.event()).isEqualTo("position")
                redis.opsForHash<String, String>().put(QueueKeys.admission("token-606"), "memberId", "1").awaitSingle()
                redis.expire(QueueKeys.admission("token-606"), properties.admissionTokenTtl).awaitSingle()
                val admitted = nextNamed(events)
                assertThat(admitted.event()).isEqualTo("admitted")
                assertThat(admitted.data()).contains("token-606")
                events.cancel()
            }
        }
    }

    @Test
    fun `마지막 대기자를 비우는 순간 들어온 등록은 활성 회차에서 빠지지 않는다`() = runTest {
        val schedule = 607L
        prepareSchedule(schedule)
        val interleaving = InterleavingRedis(redis)
        val racingAdmission = AdmissionService(interleaving, properties, clock, meters)
        queue.enter(schedule, 1)
        interleaving.beforeActiveRemove = { queue.enter(schedule, 2) }

        assertThat(racingAdmission.admit(schedule)).isEqualTo(1)

        assertThat(redis.opsForZSet().size(QueueKeys.waiting(schedule)).awaitSingle()).isEqualTo(1)
        assertThat(redis.opsForSet().isMember(QueueKeys.ACTIVE, schedule.toString()).awaitSingle()).isTrue()
        redis.delete("queue:admit-lock:{$schedule}").awaitSingle()
        assertThat(admission.admit(schedule)).isEqualTo(1)
        assertThat(redis.opsForValue().get(QueueKeys.admitted(schedule, 2)).awaitSingleOrNull()).isNotNull()
    }

    private fun stream(schedule: Long, member: Long) = clientFor(member).get().uri("/api/v1/streams/queues/$schedule")
        .exchange().expectStatus().isOk.returnResult(object : ParameterizedTypeReference<ServerSentEvent<String>>() {}).responseBody

    private suspend fun nextNamed(events: ReceiveChannel<ServerSentEvent<String>>): ServerSentEvent<String> = withTimeout(5000) {
        while (true) {
            val event = events.receive()
            if (event.event() != null) return@withTimeout event
        }
        error("unreachable")
    }
}

/** 서비스가 보내는 명령 직전에 한 번만 훅을 실행하는 Redis 템플릿. 실행 순서 재현에만 쓴다. */
class InterleavingRedis(delegate: ReactiveStringRedisTemplate) : ReactiveStringRedisTemplate(requireNotNull(delegate.connectionFactory)) {
    var beforeRegister: (suspend () -> Unit)? = null
    var beforeSeenUpdate: (suspend () -> Unit)? = null
    var beforeActiveRemove: (suspend () -> Unit)? = null
    private val setOperations: ReactiveSetOperations<String, String> = super.opsForSet()
    private val zSetOperations: ReactiveZSetOperations<String, String> = super.opsForZSet()

    override fun <T : Any> execute(script: RedisScript<T>, keys: List<String>, args: List<*>): Flux<T> {
        val source = script.scriptAsString
        val hook = when {
            source.contains("ZRANK") -> take { beforeRegister.also { beforeRegister = null } }
            source.contains("ZSCORE") && !source.contains("ZRANGE") -> take { beforeSeenUpdate.also { beforeSeenUpdate = null } }
            else -> null
        }
        val delegated = super.execute(script, keys, args)
        return if (hook == null) delegated else mono { hook() }.thenMany(delegated)
    }

    override fun opsForSet(): ReactiveSetOperations<String, String> = object : ReactiveSetOperations<String, String> by setOperations {
        override fun remove(key: String, vararg values: Any): Mono<Long> {
            val hook = if (key == QueueKeys.ACTIVE) take { beforeActiveRemove.also { beforeActiveRemove = null } } else null
            return if (hook == null) setOperations.remove(key, *values) else mono { hook() }.then(setOperations.remove(key, *values))
        }
    }

    // 폴링 갱신이 스크립트가 아닌 단일 ZADD였던 구현에서도 같은 지점에서 훅이 실행되게 한다.
    override fun opsForZSet(): ReactiveZSetOperations<String, String> = object : ReactiveZSetOperations<String, String> by zSetOperations {
        override fun add(key: String, value: String, score: Double): Mono<Boolean> {
            val hook = if (key.startsWith("queue:seen:")) take { beforeSeenUpdate.also { beforeSeenUpdate = null } } else null
            return if (hook == null) zSetOperations.add(key, value, score) else mono { hook() }.then(zSetOperations.add(key, value, score))
        }
    }

    private fun take(read: () -> (suspend () -> Unit)?): (suspend () -> Unit)? = read()
}
