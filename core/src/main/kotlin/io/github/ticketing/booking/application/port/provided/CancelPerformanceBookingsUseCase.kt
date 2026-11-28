package io.github.ticketing.booking.application.port.provided

interface CancelPerformanceBookingsUseCase {
    fun cancelBookings(performanceId: Long)
}
