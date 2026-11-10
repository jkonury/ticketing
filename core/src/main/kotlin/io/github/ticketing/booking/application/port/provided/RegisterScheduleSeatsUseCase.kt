package io.github.ticketing.booking.application.port.provided

import java.math.BigDecimal
import java.time.Instant

interface RegisterScheduleSeatsUseCase {
    /** 새로 사본을 만들었으면 true, 이미 있어 건너뛰었으면 false. */
    fun register(command: RegisterScheduleSeatsCommand): Boolean
}

data class RegisterScheduleSeatsCommand(
    val scheduleId: Long,
    val performanceId: Long,
    val performanceTitle: String,
    val organizerId: Long,
    val startsAt: Instant,
    val seats: List<SeatInfo>,
) {
    data class SeatInfo(val seatId: Long, val grade: String, val label: String, val price: BigDecimal)
}
