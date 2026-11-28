package io.github.ticketing.audit.service

import io.github.ticketing.catalog.PerformanceCancelled
import io.github.ticketing.catalog.PerformanceSuspensionChanged
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
class PerformanceAuditListener(private val recorder: AdminActionRecorder) {

    @ApplicationModuleListener
    fun on(event: PerformanceCancelled) {
        recorder.record(
            sourceEventId = event.eventId,
            actorId = event.actorId,
            action = "PERFORMANCE_CANCELLED",
            targetType = "PERFORMANCE",
            targetId = event.performanceId.toString(),
            detail = mapOf("reason" to event.reason),
            occurredAt = event.cancelledAt,
        )
    }

    @ApplicationModuleListener
    fun on(event: PerformanceSuspensionChanged) {
        val from = if (event.saleEnabled) "SUSPENDED" else "ACTIVE"
        val to = if (event.saleEnabled) "ACTIVE" else "SUSPENDED"
        recorder.record(
            sourceEventId = event.eventId,
            actorId = event.actorId,
            action = "PERFORMANCE_SUSPENSION_CHANGED",
            targetType = "PERFORMANCE",
            targetId = event.performanceId.toString(),
            detail = mapOf("from" to from, "to" to to, "reason" to event.reason),
            occurredAt = event.changedAt,
        )
    }
}
