package io.github.ticketing.catalog

import java.time.Instant
import java.util.UUID

data class PerformanceCancelled(
    val eventId: UUID,
    val actorId: Long,
    val performanceId: Long,
    val version: Long,
    val reason: String,
    val cancelledAt: Instant,
)
