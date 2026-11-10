package io.github.ticketing.booking.application.port.provided

interface CancelBookingUseCase {
    /** 이미 취소된 예매는 같은 결과를 돌려준다(멱등). */
    fun cancel(memberId: Long, bookingId: Long): BookingResult
}
