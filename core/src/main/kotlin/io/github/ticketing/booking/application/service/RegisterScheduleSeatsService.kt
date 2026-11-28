package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import io.github.ticketing.booking.application.port.provided.UpdateScheduleSaleStatusUseCase
import io.github.ticketing.booking.application.port.required.ScheduleSeatCommandPort
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.domain.ScheduleCopy
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock

@Service
class RegisterScheduleSeatsService(
    private val scheduleSeats: ScheduleSeatCommandPort,
    private val seatHold: SeatHoldPort,
    private val clock: Clock,
) : RegisterScheduleSeatsUseCase,
    UpdateScheduleSaleStatusUseCase {
    @Transactional
    override fun update(performanceId: Long, saleEnabled: Boolean, version: Long) =
        scheduleSeats.updateSaleStatus(performanceId, saleEnabled, version)

    @Transactional
    override fun register(command: RegisterScheduleSeatsCommand): Boolean {
        val scheduleId = ScheduleId(command.scheduleId)
        val schedule = ScheduleCopy(
            scheduleId,
            command.performanceId,
            command.performanceTitle,
            command.organizerId,
            command.startsAt,
            clock.instant(),
        )
        val seats = command.seats.map { ScheduleSeatCopy(scheduleId, SeatId(it.seatId), it.grade, it.label, it.price) }
        val saved = scheduleSeats.saveIfAbsent(schedule, seats)
        if (saved) {
            // Redis는 DB 트랜잭션 밖이다. 커밋 후에만 스냅샷을 만든다(HSETNX라 재수신해도 안전).
            val seatIds = seats.map { it.seatId }
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = seatHold.initSnapshot(scheduleId, seatIds)
            })
        }
        return saved
    }
}
