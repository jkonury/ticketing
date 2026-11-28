package io.github.ticketing.booking.application.port.required

import io.github.ticketing.booking.domain.ScheduleCopy
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId

interface ScheduleSeatCommandPort {
    /** 이미 있으면 아무것도 하지 않는다(멱등). 새로 저장했으면 true */
    fun saveIfAbsent(schedule: ScheduleCopy, seats: List<ScheduleSeatCopy>): Boolean

    fun findSchedule(scheduleId: ScheduleId): ScheduleCopy?

    fun isSaleEnabled(scheduleId: ScheduleId): Boolean

    fun updateSaleStatus(performanceId: Long, saleEnabled: Boolean, version: Long)

    fun existsSchedule(scheduleId: ScheduleId): Boolean

    fun findSeats(scheduleId: ScheduleId, seatIds: Collection<SeatId>): List<ScheduleSeatCopy>
}
