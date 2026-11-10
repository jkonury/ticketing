package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.application.port.provided.BookingResult
import io.github.ticketing.booking.application.port.provided.CancelBookingUseCase
import io.github.ticketing.booking.application.port.required.BookingCommandPort
import io.github.ticketing.booking.application.port.required.ScheduleSeatCommandPort
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.domain.BookingId
import io.github.ticketing.booking.domain.BookingStatus
import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.domain.CancelOutcome
import io.github.ticketing.booking.domain.CancelReason
import io.github.ticketing.payment.RefundRequested
import org.springframework.context.ApplicationEventPublisher
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.NotBookingOwnerException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

@Service
class CancelBookingService(
    private val bookings: BookingCommandPort,
    private val scheduleSeats: ScheduleSeatCommandPort,
    private val seatHold: SeatHoldPort,
    private val events: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) : CancelBookingUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    private sealed interface After {
        data object Nothing : After
        data object ReleaseHold : After
        data object MarkAvailable : After
    }

    override fun cancel(memberId: Long, bookingId: Long): BookingResult = translatingRuleViolations {
        val member = MemberId(memberId)
        val saved = tx.execute {
            val booking = bookings.findById(BookingId(bookingId)) ?: throw BookingNotFoundException()
            if (booking.memberId != member) throw NotBookingOwnerException()
            if (booking.status == BookingStatus.CANCELLED) return@execute booking to After.Nothing
            val now = clock.instant()
            val outcome = booking.cancelByUser(member, now)
            val saved = bookings.save(booking)
            val refundRequired = outcome == CancelOutcome.RefundRequired
            if (refundRequired) {
                bookings.deleteSoldSeats(BookingId(bookingId))
                events.publishEvent(RefundRequested(bookingId, CancelReason.USER_REQUESTED.name, now))
            }
            events.publishEvent(
                BookingCancelled(bookingId, memberId, booking.scheduleId.value, CancelReason.USER_REQUESTED.name, refundRequired, now),
            )
            saved to if (refundRequired) After.MarkAvailable else After.ReleaseHold
        }
        val (booking, after) = requireNotNull(saved)
        // Redis 반영은 커밋 후에 한다. 실패해도 선점은 TTL이 만료시키고 DB 제약이 중복 판매를 막는다
        val seatIds = booking.seats.map { it.seatId }
        when (after) {
            After.Nothing -> Unit
            After.ReleaseHold -> runCatching { seatHold.release(booking.scheduleId, seatIds, booking.holdToken, clock.instant()) }
                .onFailure { log.warn("예매 취소 후 선점 해제에 실패했다. bookingId={}", bookingId, it) }
            After.MarkAvailable -> runCatching { seatHold.markAvailable(booking.scheduleId, seatIds, clock.instant()) }
                .onFailure { log.warn("확정 예매 취소 후 좌석 반환에 실패했다. bookingId={}", bookingId, it) }
        }
        if (after != After.Nothing) log.info("예매 취소 bookingId={} memberId={} confirmed={}", bookingId, memberId, after == After.MarkAvailable)
        val labels = scheduleSeats.findSeats(booking.scheduleId, booking.seats.map { it.seatId }).associate { it.seatId to it.label }
        booking.toResult(labels)
    }
}
