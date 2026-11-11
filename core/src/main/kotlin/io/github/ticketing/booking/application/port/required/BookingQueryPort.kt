package io.github.ticketing.booking.application.port.required

import io.github.ticketing.booking.application.port.provided.BookingDetailView
import io.github.ticketing.booking.application.port.provided.BookingPage
import java.time.Instant

interface BookingQueryPort {
    fun findSweepCandidateIds(cutoff: Instant, paymentCutoff: Instant, afterId: Long?, limit: Int): List<Long>

    fun findMyBookings(memberId: Long, cursor: Long?, size: Int): BookingPage

    fun findDetail(bookingId: Long): BookingDetailView?
}
