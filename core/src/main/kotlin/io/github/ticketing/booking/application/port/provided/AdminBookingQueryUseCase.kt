package io.github.ticketing.booking.application.port.provided

import io.github.ticketing.booking.domain.BookingStatus
import java.math.BigDecimal
import java.time.Instant

interface AdminBookingQueryUseCase {
    fun list(filter: AdminBookingFilter): AdminBookingPage

    fun detail(bookingId: Long): BookingDetailView
}

data class AdminBookingFilter(
    val memberId: Long?,
    val scheduleId: Long?,
    val status: BookingStatus?,
    val createdFrom: Instant?,
    val createdTo: Instant?,
    val cursor: Long?,
    val size: Int,
)

data class AdminBookingSummary(
    val bookingId: Long,
    val memberId: Long,
    val scheduleId: Long,
    val performanceTitle: String,
    val status: BookingStatus,
    val totalAmount: BigDecimal,
    val createdAt: Instant,
)

data class AdminBookingPage(val items: List<AdminBookingSummary>, val nextCursor: Long?)
