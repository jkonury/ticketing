package io.github.ticketing.realtime.seat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SeatStreamBufferTests {
    @Test
    fun `소비하지 않는 연결은 버퍼가 넘치면 종료되고 같은 흐름의 다른 구독자는 계속 받는다`() = runTest {
        withContext(Dispatchers.Default) {
            val shared = MutableSharedFlow<SeatEvent>(replay = 1)
            val bufferSize = 3
            val total = 10
            val overflows = AtomicInteger()
            val slowBuffer = Channel<SeatEvent>(bufferSize)
            val healthyBuffer = Channel<SeatEvent>(Channel.UNLIMITED)
            val slowSubscribed = CompletableDeferred<Unit>()
            val healthySubscribed = CompletableDeferred<Unit>()

            coroutineScope {
                val slow = launch {
                    shared.onSubscription { slowSubscribed.complete(Unit) }.collectIntoBuffer(slowBuffer) { overflows.incrementAndGet() }
                }
                val healthy = launch {
                    shared.onSubscription {
                        healthySubscribed.complete(Unit)
                    }.collectIntoBuffer(healthyBuffer) { overflows.incrementAndGet() }
                }
                slowSubscribed.await()
                healthySubscribed.await()

                // 느린 연결이 수집을 막지 않으므로 emit은 suspend 없이 끝까지 진행해야 한다
                withTimeout(5000) { (1..total).forEach { shared.emit(event(it)) } }
                val received = withTimeout(5000) { (1..total).map { healthyBuffer.receive().id } }
                healthy.cancel()

                withTimeout(5000) { slow.join() }
                assertThat(overflows.get()).isEqualTo(1)
                assertThat(slowBuffer.isClosedForSend).isTrue()
                assertThat(slowBuffer.toList().map { it.id }).containsExactly("1-0", "2-0", "3-0")
                assertThat(received).containsExactlyElementsOf((1..total).map { "$it-0" })
            }
        }
    }

    private fun event(sequence: Int) = SeatEvent("$sequence-0", listOf(1L), "SOLD", null, 100)

    private suspend fun <T> Channel<T>.toList(): List<T> {
        val items = mutableListOf<T>()
        for (item in this) items.add(item)
        return items
    }
}
