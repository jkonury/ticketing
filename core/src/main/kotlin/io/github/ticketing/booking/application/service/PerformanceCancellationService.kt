package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.application.port.provided.CancelPerformanceBookingsUseCase
import io.github.ticketing.booking.application.port.required.BookingCommandPort
import io.github.ticketing.booking.domain.BookingStatus
import io.github.ticketing.booking.domain.CancelReason
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

@Service
class PerformanceCancellationService(
    private val bookings: BookingCommandPort,
    private val cancelBooking: CancelBookingService,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    @Value("\${ticketing.booking.performance-cancellation.chunk-size:100}") private val chunkSize: Int,
) : CancelPerformanceBookingsUseCase {
    init {
        require(chunkSize > 0) { "공연 취소 청크 크기는 양수여야 한다" }
    }

    override fun cancelBookings(performanceId: Long) {
        var afterId = 0L
        while (true) {
            afterId = tx.execute {
                val ids = bookings.findActiveIds(performanceId, afterId, chunkSize)
                val now = clock.instant()
                for (id in ids) {
                    val booking = bookings.findById(id) ?: continue
                    if (booking.status == BookingStatus.PENDING || booking.status == BookingStatus.CONFIRMED) {
                        cancelBooking.cancel(booking, CancelReason.PERFORMANCE_CANCELLED, now)
                    }
                }
                ids.lastOrNull()?.value
            } ?: return
        }
    }
}
