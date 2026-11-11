package io.github.ticketing.member.web

import io.github.ticketing.member.service.SseTicketService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class SseTicketController(private val service: SseTicketService) {
    @PostMapping("/api/v1/sse-tickets")
    fun issue(authentication: Authentication): ResponseEntity<SseTicketResponse> {
        val issued = service.issue(authentication.name.toLong())
        return ResponseEntity.status(HttpStatus.CREATED).body(SseTicketResponse(issued.ticket, issued.expiresAt))
    }
}

data class SseTicketResponse(val ticket: String, val expiresAt: Instant)
