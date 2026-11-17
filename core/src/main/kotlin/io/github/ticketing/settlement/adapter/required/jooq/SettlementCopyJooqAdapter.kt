package io.github.ticketing.settlement.adapter.required.jooq

import io.github.ticketing.settlement.application.port.provided.RecordRefundCommand
import io.github.ticketing.settlement.application.port.provided.RecordSaleCommand
import io.github.ticketing.settlement.application.port.required.SettlementCopyCommandPort
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import ticketing.jooq.settlement.tables.references.BOOKING_REFUND
import ticketing.jooq.settlement.tables.references.BOOKING_SALE
import java.time.Instant
import java.time.ZoneOffset

@Repository
class SettlementCopyJooqAdapter(private val dsl: DSLContext) : SettlementCopyCommandPort {
    override fun insertSaleIfAbsent(command: RecordSaleCommand, receivedAt: Instant): Boolean = dsl.insertInto(BOOKING_SALE)
        .set(BOOKING_SALE.BOOKING_ID, command.bookingId)
        .set(BOOKING_SALE.PERFORMANCE_ID, command.performanceId)
        .set(BOOKING_SALE.ORGANIZER_ID, command.organizerId)
        .set(BOOKING_SALE.SCHEDULE_ID, command.scheduleId)
        .set(BOOKING_SALE.AMOUNT, command.amount.longValueExact())
        .set(BOOKING_SALE.LIST_AMOUNT, command.listAmount.longValueExact())
        .set(BOOKING_SALE.DISCOUNT_AMOUNT, command.discountAmount.longValueExact())
        .set(BOOKING_SALE.CONFIRMED_AT, command.confirmedAt.atOffset(ZoneOffset.UTC))
        .set(BOOKING_SALE.RECEIVED_AT, receivedAt.atOffset(ZoneOffset.UTC))
        .onConflict(BOOKING_SALE.BOOKING_ID).doNothing().execute() == 1

    override fun insertRefundIfAbsent(command: RecordRefundCommand, receivedAt: Instant): Boolean = dsl.insertInto(BOOKING_REFUND)
        .set(BOOKING_REFUND.BOOKING_ID, command.bookingId)
        .set(BOOKING_REFUND.PERFORMANCE_ID, command.performanceId)
        .set(BOOKING_REFUND.ORGANIZER_ID, command.organizerId)
        .set(BOOKING_REFUND.REASON, command.reason)
        .set(BOOKING_REFUND.CANCELLED_AT, command.cancelledAt.atOffset(ZoneOffset.UTC))
        .set(BOOKING_REFUND.RECEIVED_AT, receivedAt.atOffset(ZoneOffset.UTC))
        .onConflict(BOOKING_REFUND.BOOKING_ID).doNothing().execute() == 1
}
