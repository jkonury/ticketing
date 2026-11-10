package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.application.port.provided.BookingDetailView
import io.github.ticketing.booking.application.port.provided.BookingPage
import io.github.ticketing.booking.application.port.provided.BookingQueryUseCase
import io.github.ticketing.booking.application.port.required.BookingQueryPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BookingQueryService(private val queries: BookingQueryPort) : BookingQueryUseCase {
    @Transactional(readOnly = true)
    override fun myBookings(memberId: Long, cursor: Long?, size: Int): BookingPage =
        queries.findMyBookings(memberId, cursor, size.coerceIn(1, BookingQueryUseCase.MAX_PAGE_SIZE))

    @Transactional(readOnly = true)
    override fun detail(memberId: Long, bookingId: Long): BookingDetailView {
        val view = queries.findDetail(bookingId) ?: throw BookingNotFoundException()
        if (view.memberId != memberId) throw BookingNotFoundException()
        return view
    }
}
