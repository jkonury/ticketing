package io.github.ticketing.booking.application.port.provided

interface CreateBookingUseCase {
    fun create(command: CreateBookingCommand): CreateBookingResult
}

data class CreateBookingCommand(val memberId: Long, val idempotencyKey: String, val scheduleId: Long, val seatIds: List<Long>)
