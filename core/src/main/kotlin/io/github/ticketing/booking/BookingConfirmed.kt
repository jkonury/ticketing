package io.github.ticketing.booking

import java.math.BigDecimal
import java.time.Instant

/** 예매가 확정되었다. notification과 settlement가 구독한다. */
data class BookingConfirmed(
    val bookingId: Long,
    val memberId: Long,
    val scheduleId: Long,
    val seatIds: List<Long>,
    val amount: BigDecimal,
    val confirmedAt: Instant,
)
