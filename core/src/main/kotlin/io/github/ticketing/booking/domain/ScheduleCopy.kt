package io.github.ticketing.booking.domain

import java.math.BigDecimal
import java.time.Instant

/** catalog 회차의 사본. ScheduleRegistered 이벤트로 받은 값만 담는다. */
data class ScheduleCopy(
    val scheduleId: ScheduleId,
    val performanceId: Long,
    val performanceTitle: String,
    val organizerId: Long,
    val startsAt: Instant,
    val receivedAt: Instant,
)

data class ScheduleSeatCopy(
    val scheduleId: ScheduleId,
    val seatId: SeatId,
    val grade: String,
    val label: String,
    val price: BigDecimal,
)
