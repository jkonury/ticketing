package io.github.ticketing.realtime.queue

import io.github.ticketing.realtime.support.memberId
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1/queues/{scheduleId}/entries")
class QueueController(private val service: QueueService) {
    @PostMapping
    suspend fun enter(@PathVariable scheduleId: Long, @RequestHeader("X-Member-Id", required = false) member: String?): QueueResponse =
        service.enter(scheduleId, memberId(member)).toResponse()

    @GetMapping("/me")
    suspend fun status(@PathVariable scheduleId: Long, @RequestHeader("X-Member-Id", required = false) member: String?): QueueResponse =
        service.status(scheduleId, memberId(member)).toResponse()

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun leave(@PathVariable scheduleId: Long, @RequestHeader("X-Member-Id", required = false) member: String?) {
        service.leave(scheduleId, memberId(member))
    }
}

data class QueueResponse(val status: String, val position: Long? = null, val admissionToken: String? = null, val expiresAt: Instant? = null)

private fun QueueStatus.toResponse(): QueueResponse = when (this) {
    is QueueStatus.Waiting -> QueueResponse(status = "WAITING", position = position)
    is QueueStatus.Admitted -> QueueResponse(status = "ADMITTED", admissionToken = admissionToken, expiresAt = expiresAt)
}
