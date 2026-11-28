package io.github.ticketing.booking.application.port.provided

fun interface UpdateScheduleSaleStatusUseCase {
    fun update(performanceId: Long, saleEnabled: Boolean, version: Long)
}
