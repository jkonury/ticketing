package io.github.ticketing.settlement.adapter.provided.event

import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
import io.github.ticketing.settlement.application.port.provided.RecordBookingEventUseCase
import io.github.ticketing.settlement.application.port.provided.RecordRefundCommand
import io.github.ticketing.settlement.application.port.provided.RecordSaleCommand
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class BookingEventListener(private val records: RecordBookingEventUseCase) {
    @KafkaListener(topics = ["#{@kafkaTopics.bookingConfirmed}"], groupId = "ticketing-settlement")
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

    @KafkaListener(topics = ["#{@kafkaTopics.bookingCancelled}"], groupId = "ticketing-settlement")
    fun on(event: BookingCancelled) {
        if (!event.refundRequired) return
        records.recordRefund(
            RecordRefundCommand(event.bookingId, event.performanceId, event.organizerId, event.reason, event.cancelledAt),
        )
    }
}
