package io.github.ticketing.booking.application.port.provided

interface ReconcileSeatSnapshotUseCase {
    fun reconcile(scheduleId: Long): Int
}
