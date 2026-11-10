package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.application.port.provided.BookingResult
import io.github.ticketing.booking.application.port.provided.BookingSeatResult
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.SeatId

internal fun Booking.toResult(labels: Map<SeatId, String>): BookingResult = BookingResult(
    bookingId = requireNotNull(id).value,
    status = status.name,
    totalAmount = totalPrice,
    expiresAt = expiresAt,
    seats = seats.map { BookingSeatResult(it.seatId.value, labels[it.seatId].orEmpty(), it.price) },
)
