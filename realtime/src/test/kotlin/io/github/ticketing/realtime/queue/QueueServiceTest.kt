package io.github.ticketing.realtime.queue

import io.github.ticketing.realtime.support.RealtimeException
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class QueueServiceTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val store = FakeQueueStore()
    private val service = QueueService(store, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `재진입은 순서를 유지하고 이탈하면 뒤 순서가 당겨진다`() = runTest {
        assertThat(service.enter(1, 10)).isEqualTo(QueueStatus.Waiting(1))
        assertThat(service.enter(1, 20)).isEqualTo(QueueStatus.Waiting(2))
        assertThat(service.enter(1, 10)).isEqualTo(QueueStatus.Waiting(1))
        service.leave(1, 10)
        service.leave(1, 10)

        assertThat(service.status(1, 20)).isEqualTo(QueueStatus.Waiting(1))
        assertThat(store.seen).containsEntry(20L, now.toEpochMilli()).doesNotContainKey(10L)
    }

    @Test
    fun `유효한 토큰을 반환하고 만료된 토큰은 제거한 뒤 대기 순서를 반환한다`() = runTest {
        service.enter(1, 10)
        store.token = "token"
        store.ttl = Duration.ofSeconds(10)

        assertThat(service.status(1, 10)).isEqualTo(QueueStatus.Admitted("token", now.plusSeconds(10)))
        store.ttl = Duration.ZERO
        assertThat(service.status(1, 10, updateSeen = false)).isEqualTo(QueueStatus.Waiting(1))
        assertThat(store.token).isNull()
    }

    @Test
    fun `회차나 대기 항목이 없으면 구분된 오류를 낸다`() = runTest {
        store.exists = false
        assertThat(assertThrows<RealtimeException> { service.enter(1, 10) }.code).isEqualTo("SCHEDULE_NOT_FOUND")
        store.exists = true
        assertThat(assertThrows<RealtimeException> { service.status(1, 10) }.code).isEqualTo("QUEUE_ENTRY_NOT_FOUND")
    }
}

internal class FakeQueueStore : QueueStore {
    var exists = true
    var token: String? = null
    var ttl: Duration? = null
    val waiting = mutableListOf<Long>()
    val seen = mutableMapOf<Long, Long>()

    override suspend fun scheduleExists(scheduleId: Long) = exists
    override suspend fun admissionToken(scheduleId: Long, memberId: Long) = token
    override suspend fun tokenTtl(token: String) = ttl
    override suspend fun removeAdmission(scheduleId: Long, memberId: Long) {
        token = null
    }
    override suspend fun addWaiting(scheduleId: Long, memberId: Long) {
        if (memberId !in waiting) waiting.add(memberId)
    }
    override suspend fun position(scheduleId: Long, memberId: Long): Long? = waiting.indexOf(memberId).takeIf { it >= 0 }?.toLong()
    override suspend fun markSeen(scheduleId: Long, memberId: Long, time: Long) {
        seen[memberId] = time
    }
    override suspend fun leave(scheduleId: Long, memberId: Long) {
        waiting.remove(memberId)
        seen.remove(memberId)
    }
}
