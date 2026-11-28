package io.github.ticketing.audit.service

import io.github.ticketing.booking.BookingForceCancelled
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
class BookingAuditListener(private val recorder: AdminActionRecorder) {
    @ApplicationModuleListener
    fun on(event: BookingForceCancelled) {
        recorder.record(
            sourceEventId = event.eventId,
            actorId = event.actorId,
            action = "BOOKING_FORCE_CANCELLED",
            targetType = "BOOKING",
            targetId = event.bookingId.toString(),
            detail = mapOf("reason" to event.reason),
            occurredAt = event.cancelledAt,
        )
    }
}
