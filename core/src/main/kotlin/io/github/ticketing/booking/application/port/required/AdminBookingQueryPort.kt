package io.github.ticketing.booking.application.port.required

import io.github.ticketing.booking.application.port.provided.AdminBookingFilter
import io.github.ticketing.booking.application.port.provided.AdminBookingPage

interface AdminBookingQueryPort {
    fun findBookings(filter: AdminBookingFilter): AdminBookingPage
}
