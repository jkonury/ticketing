package io.github.ticketing.booking.adapter.provided.event

import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import io.github.ticketing.booking.application.port.provided.UpdateScheduleSaleStatusUseCase
import io.github.ticketing.catalog.PerformanceSuspensionChanged
import io.github.ticketing.catalog.ScheduleRegistered
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/** 같은 이벤트가 다시 와도 유스케이스가 멱등이라 결과가 같다. */
@Component
class ScheduleRegisteredListener(
    private val registerScheduleSeats: RegisterScheduleSeatsUseCase,
    private val updateScheduleSaleStatus: UpdateScheduleSaleStatusUseCase,
) {
    @ApplicationModuleListener
    fun on(event: PerformanceSuspensionChanged) {
        updateScheduleSaleStatus.update(event.performanceId, event.saleEnabled, event.version)
    }

    @ApplicationModuleListener
    fun on(event: ScheduleRegistered) {
        registerScheduleSeats.register(
            RegisterScheduleSeatsCommand(
                scheduleId = event.scheduleId,
                performanceId = event.performanceId,
                performanceTitle = event.performanceTitle,
                organizerId = event.organizerId,
                startsAt = event.startsAt,
                seats = event.seats.map {
                    RegisterScheduleSeatsCommand.SeatInfo(it.seatId, it.grade, it.label, it.price)
                },
            ),
        )
    }
}
