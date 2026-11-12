package io.github.ticketing.booking.application.port.required

import io.github.ticketing.booking.application.port.provided.ScheduleAvailability
import io.github.ticketing.booking.application.port.provided.ScheduleSales
import java.time.Instant

interface ScheduleReadPort {
    fun availability(scheduleIds: List<Long>, now: Instant, paymentCutoff: Instant): List<ScheduleAvailability>
    fun sales(organizerId: Long): List<ScheduleSales>
}
