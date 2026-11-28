package io.github.ticketing.catalog

import java.time.Instant
import java.util.UUID

data class PerformanceSuspensionChanged(
    val eventId: UUID,
    val actorId: Long,
    val performanceId: Long,
    val saleEnabled: Boolean,
    val version: Long,
    val reason: String?,
    val changedAt: Instant,
)
