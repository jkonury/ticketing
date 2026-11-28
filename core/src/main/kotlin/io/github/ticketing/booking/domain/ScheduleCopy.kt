package io.github.ticketing.booking.domain

import java.math.BigDecimal
import java.time.Instant

/** catalog 회차와 판매 상태의 사본. */
data class ScheduleCopy(
    val scheduleId: ScheduleId,
    val performanceId: Long,
    val performanceTitle: String,
    val organizerId: Long,
    val startsAt: Instant,
    val receivedAt: Instant,
    val saleEnabled: Boolean = true,
    val cancelled: Boolean = false,
)

data class ScheduleSeatCopy(
    val scheduleId: ScheduleId,
    val seatId: SeatId,
    val grade: String,
    val label: String,
    val price: BigDecimal,
    val startsAt: Instant? = null,
)

fun requireSalesOpen(startsAt: Instant, now: Instant) {
    if (!now.isBefore(startsAt)) throw SalesClosedException()
}
