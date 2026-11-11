package io.github.ticketing.realtime.queue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class AdmissionServiceTest {
    private val store = FakeAdmissionStore()
    private val properties = QueueProperties(admissionBatchSize = 2)
    private val service = AdmissionService(store, properties, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))

    @Test
    fun `락을 얻은 경우 용량과 유예 기준을 전달하고 발급된 토큰을 저장한다`() = runTest {
        store.members = listOf("1", "2")

        assertThat(service.admit(3)).isEqualTo(2)
        assertThat(store.cutoff).isEqualTo(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli() - 30000)
        assertThat(store.saved.map { it.first }).containsExactly("1", "2")
        assertThat(store.saved.map { it.second }).allMatch { it.length == 43 }
        assertThat(store.removedActive).containsExactly(3L)
        assertThat(store.ttl).isEqualTo(properties.admissionTokenTtl)
    }

    @Test
    fun `락을 얻지 못하면 발급하지 않는다`() = runTest {
        store.locked = true

        assertThat(service.admit(3)).isZero()
        assertThat(store.saved).isEmpty()
        assertThat(store.removedActive).isEmpty()
    }

    @Test
    fun `회차 실패 후 다음 회차를 처리하고 취소는 전파한다`() = runTest {
        store.schedules = listOf("bad", "3")
        service.admitAll()
        assertThat(store.removedActive).containsExactly(3L)

        store.cancel = true
        assertThrows<CancellationException> { service.admitAll() }
    }
}

private class FakeAdmissionStore : AdmissionStore {
    var locked = false
    var cancel = false
    var schedules = emptyList<String>()
    var members = emptyList<String>()
    var cutoff = 0L
    var ttl: Duration? = null
    val saved = mutableListOf<Pair<String, String>>()
    val removedActive = mutableListOf<Long>()

    override suspend fun lock(scheduleId: Long, instanceId: String, duration: Duration): Boolean {
        if (cancel) throw CancellationException()
        return !locked
    }

    override suspend fun admit(scheduleId: Long, cutoff: Long, ttl: Duration, tokens: List<String>): List<Pair<String, String>> {
        this.cutoff = cutoff
        this.ttl = ttl
        return members.zip(tokens)
    }

    override suspend fun saveToken(scheduleId: Long, memberId: String, token: String, ttl: Duration) {
        saved.add(memberId to token)
    }
    override suspend fun removeActiveIfEmpty(scheduleId: Long) {
        removedActive.add(scheduleId)
    }
    override suspend fun activeSchedules() = schedules
}
