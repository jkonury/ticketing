package io.github.ticketing.booking.application.port.provided

interface BookingQueryUseCase {
    fun myBookings(memberId: Long, cursor: Long?, size: Int, performanceId: Long?): BookingPage

    /** 본인 예매가 아니면 존재를 감추기 위해 없는 것과 같게 취급한다. */
    fun detail(memberId: Long, bookingId: Long): BookingDetailView

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 50
    }
}
