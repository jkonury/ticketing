package io.github.ticketing.booking.adapter.provided.warmup

import io.github.ticketing.booking.adapter.provided.web.CreateBookingRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.ReadinessState
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

@RestController
@ConditionalOnProperty("ticketing.startup-warmup.enabled", havingValue = "true")
internal class BookingWarmupController(private val work: BookingWarmupWork, private val availability: ApplicationAvailability) {
    private val expiresAt = System.nanoTime() + Duration.ofMinutes(3).toNanos()
    private val remaining = AtomicInteger(MAX_REQUESTS)
    private val concurrency = Semaphore(MAX_CONCURRENCY)

    private companion object {
        const val MAX_REQUESTS = 30000
        const val MAX_CONCURRENCY = 1000
    }

    @PostMapping("/internal/startup-warmup")
    @PreAuthorize("hasRole('STARTUP_WARMUP')")
    fun exercise(
        @RequestParam(defaultValue = "true") conflict: Boolean,
        @RequestHeader("Idempotency-Key") @Size(min = 1, max = 100) idempotencyKey: String,
        @Valid @RequestBody request: CreateBookingRequest,
    ): ResponseEntity<Map<String, Any>> {
        validateTemplate(idempotencyKey, request)
        if (availability.readinessState != ReadinessState.ACCEPTING_TRAFFIC || System.nanoTime() >= expiresAt ||
            remaining.getAndDecrement() <= 0
        ) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        }
        if (!concurrency.tryAcquire()) throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS)
        val counts = WarmupIoCounts()
        return try {
            val winner = WarmupIo.measure(counts) { work.exercise(conflict) }
            ResponseEntity.status(if (winner) HttpStatus.CREATED else HttpStatus.CONFLICT)
                .body(mapOf("winner" to winner, "dbStatements" to counts.dbStatements.get(), "redisCommands" to counts.redisCommands.get()))
        } finally {
            concurrency.release()
        }
    }

    private fun validateTemplate(idempotencyKey: String, request: CreateBookingRequest) {
        if (idempotencyKey != "startup-warmup" || request.scheduleId != 1L || request.seatIds != listOf(1L)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
    }
}
