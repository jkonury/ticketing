package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.application.port.provided.ReconcileSeatSnapshotUseCase
import io.github.ticketing.booking.application.port.required.BookingQueryPort
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class SeatSnapshotQueries(private val queries: BookingQueryPort) {
    @Transactional(readOnly = true)
    fun upcoming(now: Instant, afterId: Long?, limit: Int) = queries.findUpcomingScheduleIds(now, afterId, limit)

    @Transactional(readOnly = true)
    fun seats(scheduleId: Long) = queries.findSeatIds(scheduleId)

    @Transactional(readOnly = true)
    fun sold(scheduleId: Long) = queries.findSoldSeatIds(scheduleId)
}

/** 스냅샷은 표시용이며 최종 판매 판단은 선점 키와 DB 유니크 제약에 의존한다. */
@Service
class ReconcileSeatSnapshotService(private val queries: SeatSnapshotQueries, private val holds: SeatHoldPort, private val clock: Clock) :
    ReconcileSeatSnapshotUseCase {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun reconcile(scheduleId: Long): Int {
        val seats = queries.seats(scheduleId).map(::SeatId)
        val sold = queries.sold(scheduleId).map(::SeatId).toSet()
        val id = ScheduleId(scheduleId)
        val changed = holds.reconcileSnapshot(id, seats, sold, clock.instant())
        val latestSold = queries.sold(scheduleId).map(::SeatId).toSet()
        val changedSeats = (latestSold - sold) + (sold - latestSold)
        return changed + holds.reconcileSnapshot(id, changedSeats.toList(), latestSold intersect changedSeats, clock.instant())
    }
}
