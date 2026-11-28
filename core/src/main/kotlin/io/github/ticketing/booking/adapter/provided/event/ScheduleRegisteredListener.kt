package io.github.ticketing.booking.adapter.provided.event

import io.github.ticketing.booking.application.port.provided.CancelPerformanceBookingsUseCase
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import io.github.ticketing.booking.application.port.provided.UpdateScheduleSaleStatusUseCase
import io.github.ticketing.catalog.PerformanceCancelled
import io.github.ticketing.catalog.PerformanceSuspensionChanged
import io.github.ticketing.catalog.ScheduleRegistered
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation

/** 같은 이벤트가 다시 와도 유스케이스가 멱등이라 결과가 같다. */
@Component
class ScheduleRegisteredListener(
    private val registerScheduleSeats: RegisterScheduleSeatsUseCase,
    private val updateScheduleSaleStatus: UpdateScheduleSaleStatusUseCase,
    private val performanceCancellation: CancelPerformanceBookingsUseCase,
) {
    // 사본을 먼저 커밋하고 청크마다 하나의 커넥션만 사용한다.
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    fun on(event: PerformanceCancelled) {
        updateScheduleSaleStatus.update(event.performanceId, saleEnabled = false, version = event.version, cancelled = true)
        performanceCancellation.cancelBookings(event.performanceId)
    }

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
