package io.github.ticketing.realtime.queue

import io.github.ticketing.realtime.support.RealtimeException
import io.github.ticketing.realtime.support.memberId
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class QueuePositionStreamController(private val service: QueueService, private val properties: QueueProperties) {
    @GetMapping("/api/v1/streams/queues/{scheduleId}", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun stream(
        @PathVariable scheduleId: Long,
        @RequestHeader("X-Member-Id", required = false) member: String?,
    ): Flow<ServerSentEvent<Any>> {
        val memberId = memberId(member)
        val initial = service.status(scheduleId, memberId)
        return channelFlow {
            val heartbeat = launch {
                while (true) {
                    delay(properties.heartbeatInterval.toMillis())
                    try {
                        service.status(scheduleId, memberId)
                    } catch (exception: RealtimeException) {
                        if (exception.code != "QUEUE_ENTRY_NOT_FOUND") throw exception
                    }
                    send(ServerSentEvent.builder<Any>().comment("heartbeat").build())
                }
            }
            try {
                var current: QueueStatus? = initial
                var previousPosition: Long? = null
                while (current != null) {
                    when (current) {
                        is QueueStatus.Waiting -> if (current.position != previousPosition) {
                            send(ServerSentEvent.builder<Any>(PositionData(current.position)).event("position").build())
                            previousPosition = current.position
                        }

                        is QueueStatus.Admitted -> {
                            send(
                                ServerSentEvent.builder<Any>(
                                    AdmissionData(current.admissionToken, current.expiresAt),
                                ).event("admitted").build(),
                            )
                        }

                        null -> Unit
                    }
                    current = if (current is QueueStatus.Admitted) {
                        null
                    } else {
                        delay(properties.positionPollInterval.toMillis())
                        try {
                            service.status(scheduleId, memberId, updateSeen = false)
                        } catch (exception: RealtimeException) {
                            if (exception.code != "QUEUE_ENTRY_NOT_FOUND") throw exception
                            send(ServerSentEvent.builder<Any>().event("removed").build())
                            null
                        }
                    }
                }
            } finally {
                heartbeat.cancelAndJoin()
            }
        }
    }
}

data class PositionData(val position: Long)

data class AdmissionData(val admissionToken: String, val expiresAt: Instant)
