package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import io.github.ticketing.booking.application.port.required.ScheduleSeatCommandPort
import io.github.ticketing.booking.domain.ScheduleCopy
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class RegisterScheduleSeatsService(
    private val scheduleSeats: ScheduleSeatCommandPort,
    private val clock: Clock,
) : RegisterScheduleSeatsUseCase {
    @Transactional
    override fun register(command: RegisterScheduleSeatsCommand): Boolean {
        val scheduleId = ScheduleId(command.scheduleId)
        val schedule = ScheduleCopy(
            scheduleId, command.performanceId, command.performanceTitle,
            command.organizerId, command.startsAt, clock.instant(),
        )
        val seats = command.seats.map { ScheduleSeatCopy(scheduleId, SeatId(it.seatId), it.grade, it.label, it.price) }
        return scheduleSeats.saveIfAbsent(schedule, seats)
    }
}
