package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.application.port.provided.AdminBookingFilter
import io.github.ticketing.booking.application.port.provided.AdminBookingPage
import io.github.ticketing.booking.application.port.provided.AdminBookingQueryUseCase
import io.github.ticketing.booking.application.port.provided.BookingDetailView
import io.github.ticketing.booking.application.port.required.AdminBookingQueryPort
import io.github.ticketing.booking.application.port.required.BookingQueryPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AdminBookingQueryService(private val queries: AdminBookingQueryPort, private val details: BookingQueryPort) :
    AdminBookingQueryUseCase {
    override fun list(filter: AdminBookingFilter): AdminBookingPage {
        if (filter.createdFrom != null && filter.createdTo != null && filter.createdFrom > filter.createdTo) {
            throw InvalidBookingPeriodException()
        }
        return queries.findBookings(filter)
    }

    override fun detail(bookingId: Long): BookingDetailView = details.findDetail(bookingId) ?: throw BookingNotFoundException()
}
