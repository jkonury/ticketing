package io.github.ticketing.member.web

import io.github.ticketing.member.service.SseTicketService
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class SseTicketController(private val service: SseTicketService) {
    @PostMapping("/api/v1/sse-tickets")
    @ResponseStatus(HttpStatus.CREATED)
    fun issue(authentication: Authentication): SseTicketResponse {
        val issued = service.issue(authentication.name.toLong())
        return SseTicketResponse(issued.ticket, issued.expiresAt)
    }
}

data class SseTicketResponse(val ticket: String, val expiresAt: Instant)
