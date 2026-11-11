package io.github.ticketing.booking.application.port.provided

interface ExpireBookingUseCase {
    fun expireIfDue(bookingId: Long): ExpireResult
}

sealed interface ExpireResult {
    data object Expired : ExpireResult

    data class Skipped(val reason: String) : ExpireResult
}
