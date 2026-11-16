package io.github.ticketing.booking.adapter.provided.warmup

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.ReadinessState
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

@RestController
@ConditionalOnProperty("ticketing.startup-warmup.enabled", havingValue = "true")
internal class BookingWarmupController(private val work: BookingWarmupWork, private val availability: ApplicationAvailability) {
    private val expiresAt = System.nanoTime() + Duration.ofMinutes(2).toNanos()
    private val remaining = AtomicInteger(8)
    private val concurrency = Semaphore(2)

    @PostMapping("/internal/startup-warmup")
    @PreAuthorize("hasRole('STARTUP_WARMUP')")
    fun exercise(@RequestParam(defaultValue = "true") conflict: Boolean): ResponseEntity<Map<String, Any>> {
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
}
