package io.github.ticketing.realtime.queue

import io.github.ticketing.realtime.MutableClock
import io.github.ticketing.realtime.RealtimeIntegrationTestSupport
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration

@TestPropertySource(properties = ["ticketing.realtime.queue.admission-batch-size=2"])
class AdmissionServiceTests(
    client: WebTestClient,
    redis: ReactiveStringRedisTemplate,
    private val service: AdmissionService,
    private val clock: MutableClock,
    private val meters: MeterRegistry,
) : RealtimeIntegrationTestSupport(client, redis) {
    @Test
    fun `실제 입장과 생존 신호가 없는 제거 명시 이탈을 구분해 센다`() = runTest {
        val schedule = 205L
        prepareSchedule(schedule)
        val admittedBefore = meters.counter("queue.admission", "result", "lookup_created").count()
        val missingBefore = meters.counter("queue.departure", "reason", "seen_missing").count()
        val explicitBefore = meters.counter("queue.departure", "reason", "explicit").count()
        enter(schedule, 1)
        enter(schedule, 2)
        enter(schedule, 3)
        redis.opsForZSet().remove(QueueKeys.seen(schedule), "1").awaitSingle()
        clientFor(3).delete().uri(path(schedule) + "/me").exchange().expectStatus().isNoContent

        assertThat(service.admit(schedule)).isEqualTo(1)

        assertThat(meters.counter("queue.admission", "result", "lookup_created").count() - admittedBefore).isEqualTo(1.0)
        assertThat(meters.counter("queue.departure", "reason", "seen_missing").count() - missingBefore).isEqualTo(1.0)
        assertThat(meters.counter("queue.departure", "reason", "explicit").count() - explicitBefore).isEqualTo(1.0)
    }

    @Test
    fun `앞의 두 명만 입장하고 토큰과 남은 순번을 조회한다`() = runTest {
        val schedule = 201L
        prepareSchedule(schedule)
        (1L..5L).forEach { enter(schedule, it) }

        assertThat(service.admit(schedule)).isEqualTo(2)
        assertThat(service.admit(schedule)).isZero()
        for (member in 1L..2L) {
            val token = redis.opsForValue().get(QueueKeys.admitted(schedule, member)).awaitSingle()
            clientFor(member).get().uri(path(schedule) + "/me").exchange().expectStatus().isOk.expectBody()
                .jsonPath("$.status").isEqualTo("ADMITTED").jsonPath("$.admissionToken").isEqualTo(token)
            clientFor(member).post().uri(path(schedule)).exchange().expectStatus().isOk.expectBody()
                .jsonPath("$.status").isEqualTo("ADMITTED").jsonPath("$.admissionToken").isEqualTo(token)
            val tokenKey = QueueKeys.admission(token)
            assertThat(redis.opsForHash<String, String>().entries(tokenKey).collectMap({ it.key }, { it.value }).awaitSingle())
                .containsEntry("memberId", member.toString()).containsEntry("scheduleId", schedule.toString())
            assertThat(redis.getExpire(tokenKey).awaitSingle()).isPositive()
        }
        (3L..5L).forEach { member ->
            clientFor(member).get().uri(path(schedule) + "/me").exchange().expectStatus().isOk.expectBody()
                .jsonPath("$.position").isEqualTo((member - 2).toInt())
        }
    }

    @Test
    fun `유예가 지난 회원을 제거하고 살아 있는 회원을 입장시킨다`() = runTest {
        val schedule = 202L
        prepareSchedule(schedule)
        enter(schedule, 1)
        clock.advance(Duration.ofSeconds(31))
        try {
            enter(schedule, 2)

            assertThat(service.admit(schedule)).isEqualTo(1)
            assertThat(redis.opsForZSet().size(QueueKeys.waiting(schedule)).awaitSingle()).isZero()
            assertThat(redis.opsForZSet().size(QueueKeys.seen(schedule)).awaitSingle()).isZero()
            assertThat(redis.opsForValue().get(QueueKeys.admitted(schedule, 1)).hasElement().awaitSingle()).isFalse()
            assertThat(redis.opsForValue().get(QueueKeys.admitted(schedule, 2)).hasElement().awaitSingle()).isTrue()
            assertThat(redis.opsForSet().isMember(QueueKeys.ACTIVE, schedule.toString()).awaitSingle()).isFalse()
        } finally {
            clock.reset()
        }
    }

    @Test
    fun `모든 활성 회차를 처리한다`() = runTest {
        val schedules = listOf(203L, 204L)
        schedules.forEach { schedule ->
            prepareSchedule(schedule)
            enter(schedule, 1)
        }

        service.admitAll()

        schedules.forEach { schedule ->
            assertThat(redis.opsForValue().get(QueueKeys.admitted(schedule, 1)).hasElement().awaitSingle()).isTrue()
            assertThat(redis.opsForSet().isMember(QueueKeys.ACTIVE, schedule.toString()).awaitSingle()).isFalse()
        }
    }

    private fun enter(schedule: Long, member: Long) {
        clientFor(member).post().uri(path(schedule)).exchange().expectStatus().isOk
    }

    private fun path(schedule: Long) = "/api/v1/queues/$schedule/entries"
}
