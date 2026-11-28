package io.github.ticketing.booking

import java.time.Instant
import java.util.UUID

data class BookingForceCancelled(val eventId: UUID, val actorId: Long, val bookingId: Long, val reason: String, val cancelledAt: Instant)
