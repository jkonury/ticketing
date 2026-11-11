package io.github.ticketing.settlement.application.port.provided

import java.math.BigDecimal
import java.time.Instant

interface RecordBookingEventUseCase {
    fun recordSale(command: RecordSaleCommand)
    fun recordRefund(command: RecordRefundCommand)
}

data class RecordSaleCommand(
    val bookingId: Long,
    val performanceId: Long,
    val organizerId: Long,
    val scheduleId: Long,
    val amount: BigDecimal,
    val confirmedAt: Instant,
)

data class RecordRefundCommand(
    val bookingId: Long,
    val performanceId: Long,
    val organizerId: Long,
    val reason: String,
    val cancelledAt: Instant,
)
