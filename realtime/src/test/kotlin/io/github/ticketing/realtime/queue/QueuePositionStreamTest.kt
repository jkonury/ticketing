package io.github.ticketing.realtime.queue

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class QueuePositionStreamTest {
    @Test
    fun `순번 변경과 입장을 전송하고 스트림을 종료한다`() = runTest {
        val store = FakeQueueStore()
        val service = QueueService(store, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        val controller = QueuePositionStreamController(service, QueueProperties(heartbeatInterval = Duration.ofSeconds(10)))
        service.enter(1, 10)
        service.enter(1, 20)
        val stream = controller.stream(1, "20")
        val events = async { stream.toList() }
        runCurrent()
        service.leave(1, 10)
        advanceTimeBy(1000)
        runCurrent()
        store.token = "token"
        store.ttl = Duration.ofSeconds(10)
        advanceTimeBy(1000)
        runCurrent()

        assertThat(events.await().map { it.event() }).containsExactly("position", "position", "admitted")
    }

    @Test
    fun `heartbeat는 접속 신호를 갱신하고 이탈은 removed를 보낸다`() = runTest {
        val store = FakeQueueStore()
        val service = QueueService(store, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        val controller = QueuePositionStreamController(
            service,
            QueueProperties(positionPollInterval = Duration.ofSeconds(2), heartbeatInterval = Duration.ofMillis(1500)),
        )
        service.enter(1, 10)
        store.seen.clear()
        val stream = controller.stream(1, "10")
        store.seen.clear()
        val events = async { stream.take(3).toList() }
        runCurrent()
        advanceTimeBy(1500)
        runCurrent()
        assertThat(store.seen).containsKey(10L)
        service.leave(1, 10)
        advanceTimeBy(500)
        runCurrent()

        assertThat(events.await().map { it.event() }).containsExactly("position", null, "removed")
    }
}
