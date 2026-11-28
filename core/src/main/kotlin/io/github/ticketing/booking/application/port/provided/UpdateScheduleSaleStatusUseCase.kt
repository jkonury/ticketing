package io.github.ticketing.booking.application.port.provided

interface UpdateScheduleSaleStatusUseCase {
    fun update(performanceId: Long, saleEnabled: Boolean, version: Long, cancelled: Boolean = false)
}
