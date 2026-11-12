package io.github.ticketing.booking.application.port.provided

import java.math.BigDecimal
import java.time.Instant

data class ScheduleAvailability(val scheduleId: Long, val totalSeats: Int, val availableSeats: Int)

data class ScheduleSales(
    val performanceId: Long,
    val performanceTitle: String,
    val scheduleId: Long,
    val startsAt: Instant,
    val totalSeats: Int,
    val soldSeats: Int,
    val confirmedAmount: BigDecimal,
)

interface ScheduleReadUseCase {
    fun availability(scheduleIds: List<Long>): List<ScheduleAvailability>
    fun sales(organizerId: Long): List<ScheduleSales>
}
