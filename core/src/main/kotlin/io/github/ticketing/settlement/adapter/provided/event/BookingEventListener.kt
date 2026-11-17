package io.github.ticketing.settlement.adapter.provided.event

import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
import io.github.ticketing.settlement.application.port.provided.RecordBookingEventUseCase
import io.github.ticketing.settlement.application.port.provided.RecordRefundCommand
import io.github.ticketing.settlement.application.port.provided.RecordSaleCommand
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
class BookingEventListener(private val records: RecordBookingEventUseCase) {
    @ApplicationModuleListener
    fun on(event: BookingConfirmed) {
        records.recordSale(
            RecordSaleCommand(
                event.bookingId,
                event.performanceId,
                event.organizerId,
                event.scheduleId,
                event.amount,
                event.listAmount,
                event.discountAmount,
                event.confirmedAt,
            ),
        )
    }

    @ApplicationModuleListener
    fun on(event: BookingCancelled) {
        if (!event.refundRequired) return
        records.recordRefund(
            RecordRefundCommand(event.bookingId, event.performanceId, event.organizerId, event.reason, event.cancelledAt),
        )
    }
}
