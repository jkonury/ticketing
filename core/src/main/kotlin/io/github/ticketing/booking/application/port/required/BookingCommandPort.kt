package io.github.ticketing.booking.application.port.required

import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.BookingId
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import java.time.Instant

interface BookingCommandPort {
    fun save(booking: Booking): Booking

    fun findById(id: BookingId): Booking?

    fun findByMemberAndIdempotencyKey(memberId: MemberId, key: String): Booking?

    fun findActiveIds(performanceId: Long, afterId: Long, limit: Int): List<BookingId>

    /** INSERT ... ON CONFLICT DO NOTHING. 삽입된 행 수를 반환한다. */
    fun insertSoldSeats(bookingId: BookingId, scheduleId: ScheduleId, seatIds: List<SeatId>, now: Instant): Int

    fun deleteSoldSeats(bookingId: BookingId)
}
