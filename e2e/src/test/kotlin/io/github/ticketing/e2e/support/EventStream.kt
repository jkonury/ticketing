package io.github.ticketing.e2e.support

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class EventStream(scope: CoroutineScope, events: Flow<SseEvent>) {
    private val channel = Channel<SseEvent>(Channel.UNLIMITED)
    private val job = scope.launch(Dispatchers.IO) {
        try {
            events.collect { channel.send(it) }
        } finally {
            channel.close()
        }
    }

    suspend fun next(): SseEvent = withContext(Dispatchers.IO) { withTimeout(30000) { channel.receive() } }

    suspend fun next(name: String): SseEvent {
        while (true) {
            val event = next()
            if (event.event == name) return event
        }
    }

    fun close() = job.cancel()
}
