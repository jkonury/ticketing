package io.github.ticketing.realtime.queue

import io.github.ticketing.realtime.RealtimeIntegrationTestSupport
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration

class QueueApiTests(client: WebTestClient, redis: ReactiveStringRedisTemplate) : RealtimeIntegrationTestSupport(client, redis) {
    @Test
    fun `등록 순서대로 순번을 부여하고 재등록하면 유지한다`() = runTest {
        val schedule = 101L
        prepareSchedule(schedule)
        val path = "/api/v1/queues/$schedule/entries"

        (1L..3L).forEach { member ->
            clientFor(member).post().uri(path).exchange().expectStatus().isOk.expectBody()
                .jsonPath("$.status").isEqualTo("WAITING").jsonPath("$.position").isEqualTo(member.toInt())
        }
        clientFor(2).post().uri(path).exchange().expectStatus().isOk.expectBody().jsonPath("$.position").isEqualTo(2)
    }

    @Test
    fun `상태 조회는 순번과 마지막 신호를 갱신한다`() = runTest {
        val schedule = 102L
        prepareSchedule(schedule)
        val path = "/api/v1/queues/$schedule/entries"
        clientFor(1).post().uri(path).exchange().expectStatus().isOk
        redis.opsForZSet().add(QueueKeys.seen(schedule), "1", 1.0).awaitSingle()

        clientFor(1).get().uri("$path/me").exchange().expectStatus().isOk.expectBody().jsonPath("$.position").isEqualTo(1)
        assertThat(redis.opsForZSet().score(QueueKeys.seen(schedule), "1").awaitSingle()).isEqualTo(now.toEpochMilli().toDouble())
    }

    @Test
    fun `유효한 입장 토큰을 반환하고 없는 토큰은 정리한다`() = runTest {
        val schedule = 103L
        prepareSchedule(schedule)
        val path = "/api/v1/queues/$schedule/entries"
        redis.opsForValue().set(QueueKeys.admitted(schedule, 1), "token", Duration.ofMinutes(10)).awaitSingle()
        redis.opsForHash<String, String>().put(QueueKeys.admission("token"), "memberId", "1").awaitSingle()
        redis.expire(QueueKeys.admission("token"), Duration.ofMinutes(10)).awaitSingle()

        clientFor(1).post().uri(path).exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.status").isEqualTo("ADMITTED").jsonPath("$.admissionToken").isEqualTo("token").jsonPath("$.expiresAt").exists()
        clientFor(1).get().uri("$path/me").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.status").isEqualTo("ADMITTED").jsonPath("$.admissionToken").isEqualTo("token")
        redis.delete(QueueKeys.admission("token")).awaitSingle()
        clientFor(
            1,
        ).get().uri("$path/me").exchange().expectStatus().isNotFound.expectBody().jsonPath("$.code").isEqualTo("QUEUE_ENTRY_NOT_FOUND")
        redis.opsForValue().set(QueueKeys.admitted(schedule, 1), "token", Duration.ofMinutes(10)).awaitSingle()
        clientFor(1).post().uri(path).exchange().expectStatus().isOk.expectBody().jsonPath("$.status").isEqualTo("WAITING")
    }

    @Test
    fun `이탈은 멱등이고 뒤에 있던 순번이 당겨진다`() = runTest {
        val schedule = 104L
        prepareSchedule(schedule)
        val path = "/api/v1/queues/$schedule/entries"
        clientFor(1).post().uri(path).exchange().expectStatus().isOk
        clientFor(2).post().uri(path).exchange().expectStatus().isOk

        repeat(2) { clientFor(1).delete().uri("$path/me").exchange().expectStatus().isNoContent }
        clientFor(
            1,
        ).get().uri("$path/me").exchange().expectStatus().isNotFound.expectBody().jsonPath("$.code").isEqualTo("QUEUE_ENTRY_NOT_FOUND")
        clientFor(2).get().uri("$path/me").exchange().expectStatus().isOk.expectBody().jsonPath("$.position").isEqualTo(1)
    }

    @Test
    fun `회차가 없거나 회원 헤더가 유효하지 않으면 오류를 반환한다`() {
        val path = "/api/v1/queues/105/entries"

        clientFor(1).post().uri(path).exchange().expectStatus().isNotFound.expectBody().jsonPath("$.code").isEqualTo("SCHEDULE_NOT_FOUND")
        clientFor(
            1,
        ).get().uri("$path/me").exchange().expectStatus().isNotFound.expectBody().jsonPath("$.code").isEqualTo("SCHEDULE_NOT_FOUND")
        client.post().uri(path).exchange().expectStatus().isUnauthorized.expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED")
        client.post().uri(
            path,
        ).header("X-Member-Id", "bad").exchange().expectStatus().isUnauthorized.expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED")
    }
}
