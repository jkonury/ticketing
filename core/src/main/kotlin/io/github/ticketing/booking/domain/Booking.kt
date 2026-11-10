package io.github.ticketing.booking.domain

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

data class BookedSeat(val seatId: SeatId, val price: BigDecimal)

class Booking private constructor(
    val id: BookingId?,
    val memberId: MemberId,
    val scheduleId: ScheduleId,
    val seats: List<BookedSeat>,
    val holdToken: HoldToken,
    val createdAt: Instant,
    val expiresAt: Instant,
    status: BookingStatus,
    cancelReason: CancelReason?,
    paymentRequestedAt: Instant?,
    confirmedAt: Instant?,
) {
    var status: BookingStatus = status
        private set
    var cancelReason: CancelReason? = cancelReason
        private set
    var paymentRequestedAt: Instant? = paymentRequestedAt
        private set
    var confirmedAt: Instant? = confirmedAt
        private set

    val totalPrice: BigDecimal
        get() = seats.fold(BigDecimal.ZERO) { sum, seat -> sum + seat.price }

    fun isExpired(now: Instant): Boolean = !now.isBefore(expiresAt)

    fun requestPayment(now: Instant) {
        if (status != BookingStatus.PENDING) throw InvalidBookingStateException(status)
        if (paymentRequestedAt != null) return
        if (isExpired(now)) throw BookingExpiredException()
        paymentRequestedAt = now
    }

    fun onPaymentSucceeded(now: Instant): PaymentOutcome = when (status) {
        BookingStatus.PENDING ->
            if (isExpired(now)) {
                status = BookingStatus.EXPIRED
                PaymentOutcome.RefundRequired(RefundReason.BOOKING_EXPIRED)
            } else {
                status = BookingStatus.CONFIRMED
                confirmedAt = now
                PaymentOutcome.Confirmed
            }
        BookingStatus.CONFIRMED -> PaymentOutcome.AlreadyConfirmed
        BookingStatus.EXPIRED -> PaymentOutcome.RefundRequired(RefundReason.BOOKING_EXPIRED)
        BookingStatus.CANCELLED -> PaymentOutcome.RefundRequired(RefundReason.BOOKING_CANCELLED)
    }

    fun onPaymentFailed(now: Instant) {
        if (status != BookingStatus.PENDING) return
        status = BookingStatus.CANCELLED
        cancelReason = CancelReason.PAYMENT_FAILED
    }

    /** 결제는 성공했으나 확정 시 좌석이 이미 판매된 경우. */
    fun failConfirmation(now: Instant) {
        if (status != BookingStatus.PENDING) throw InvalidBookingStateException(status)
        status = BookingStatus.CANCELLED
        cancelReason = CancelReason.SEAT_ALREADY_SOLD
    }

    fun expire(now: Instant) {
        if (status != BookingStatus.PENDING) throw InvalidBookingStateException(status)
        if (!isExpired(now)) throw InvalidBookingStateException(status)
        status = BookingStatus.EXPIRED
    }

    fun cancelByUser(memberId: MemberId, now: Instant): CancelOutcome {
        if (this.memberId != memberId) throw NotBookingOwnerException()
        return when (status) {
            BookingStatus.PENDING -> {
                status = BookingStatus.CANCELLED
                cancelReason = CancelReason.USER_REQUESTED
                CancelOutcome.ReleasedHold
            }
            BookingStatus.CONFIRMED -> {
                status = BookingStatus.CANCELLED
                cancelReason = CancelReason.USER_REQUESTED
                CancelOutcome.RefundRequired
            }
            BookingStatus.EXPIRED, BookingStatus.CANCELLED -> throw InvalidBookingStateException(status)
        }
    }

    companion object {
        const val MAX_SEATS = 4
        val HOLD_DURATION: Duration = Duration.ofMinutes(5)

        fun create(
            memberId: MemberId,
            scheduleId: ScheduleId,
            seats: List<BookedSeat>,
            holdToken: HoldToken,
            now: Instant,
        ): Booking {
            if (seats.isEmpty()) throw NoSeatsException()
            if (seats.size > MAX_SEATS) throw TooManySeatsException()
            if (seats.map { it.seatId }.toSet().size != seats.size) throw DuplicateSeatException()
            return Booking(
                id = null,
                memberId = memberId,
                scheduleId = scheduleId,
                seats = seats.toList(),
                holdToken = holdToken,
                createdAt = now,
                expiresAt = now + HOLD_DURATION,
                status = BookingStatus.PENDING,
                cancelReason = null,
                paymentRequestedAt = null,
                confirmedAt = null,
            )
        }

        /** 영속 상태에서 복원한다. 규칙 검증은 하지 않는다. */
        fun restore(
            id: BookingId,
            memberId: MemberId,
            scheduleId: ScheduleId,
            seats: List<BookedSeat>,
            holdToken: HoldToken,
            createdAt: Instant,
            expiresAt: Instant,
            status: BookingStatus,
            cancelReason: CancelReason?,
            paymentRequestedAt: Instant?,
            confirmedAt: Instant?,
        ): Booking = Booking(
            id, memberId, scheduleId, seats.toList(), holdToken, createdAt, expiresAt,
            status, cancelReason, paymentRequestedAt, confirmedAt,
        )
    }
}
