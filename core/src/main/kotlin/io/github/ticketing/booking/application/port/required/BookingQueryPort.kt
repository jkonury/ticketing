package io.github.ticketing.booking.application.port.required

import io.github.ticketing.booking.application.port.provided.BookingDetailView
import io.github.ticketing.booking.application.port.provided.BookingPage

interface BookingQueryPort {
    fun findMyBookings(memberId: Long, cursor: Long?, size: Int): BookingPage

    fun findDetail(bookingId: Long): BookingDetailView?
}
