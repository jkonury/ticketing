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
    val idempotencyKey: String,
    val version: Long?,
    val createdAt: Instant,
    val expiresAt: Instant,
    status: BookingStatus,
    cancelReason: CancelReason?,
    paymentRequestedAt: Instant?,
    confirmedAt: Instant?,
    cancelledAt: Instant?,
) {
    var status: BookingStatus = status
        private set
    var cancelReason: CancelReason? = cancelReason
        private set
    var paymentRequestedAt: Instant? = paymentRequestedAt
        private set
    var confirmedAt: Instant? = confirmedAt
        private set
    var cancelledAt: Instant? = cancelledAt
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

    /**
     * 결제 성공을 판정한다. 확정 가능한 PENDING은 상태를 바꾸지 않고 [PaymentOutcome.ConfirmationRequired]를 돌려준다.
     * 호출자가 sold_seat 삽입에 성공한 뒤 [confirm]을, 좌석 충돌이면 [failConfirmation]을 부른다.
     */
    fun onPaymentSucceeded(now: Instant, paidAmount: BigDecimal): PaymentOutcome = when (status) {
        BookingStatus.PENDING ->
            if (isExpired(now)) {
                status = BookingStatus.EXPIRED
                PaymentOutcome.RefundRequired(RefundReason.BOOKING_EXPIRED)
            } else if (paidAmount.compareTo(totalPrice) != 0) {
                status = BookingStatus.CANCELLED
                cancelledAt = now
                cancelReason = CancelReason.AMOUNT_MISMATCH
                PaymentOutcome.AmountMismatch
            } else {
                PaymentOutcome.ConfirmationRequired
            }

        BookingStatus.CONFIRMED -> PaymentOutcome.AlreadyConfirmed

        BookingStatus.EXPIRED -> PaymentOutcome.RefundRequired(RefundReason.BOOKING_EXPIRED)

        BookingStatus.CANCELLED -> PaymentOutcome.RefundRequired(RefundReason.BOOKING_CANCELLED)
    }

    /** 좌석 판매 기록이 끝난 뒤 확정한다. */
    fun confirm(now: Instant) {
        if (status != BookingStatus.PENDING) throw InvalidBookingStateException(status)
        if (isExpired(now)) throw BookingExpiredException()
        status = BookingStatus.CONFIRMED
        confirmedAt = now
    }

    fun onPaymentFailed(now: Instant) {
        if (status != BookingStatus.PENDING) return
        status = BookingStatus.CANCELLED
        cancelledAt = now
        cancelReason = CancelReason.PAYMENT_FAILED
    }

    /** 결제는 성공했으나 확정 시 좌석이 이미 판매된 경우. */
    fun failConfirmation(now: Instant) {
        if (status != BookingStatus.PENDING) throw InvalidBookingStateException(status)
        status = BookingStatus.CANCELLED
        cancelledAt = now
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
                cancelledAt = now
                cancelReason = CancelReason.USER_REQUESTED
                CancelOutcome.ReleasedHold
            }

            BookingStatus.CONFIRMED -> {
                status = BookingStatus.CANCELLED
                cancelledAt = now
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
            idempotencyKey: String,
        ): Booking {
            seatViolation(seats)?.let { throw it }
            return Booking(
                id = null,
                memberId = memberId,
                scheduleId = scheduleId,
                seats = seats.toList(),
                holdToken = holdToken,
                idempotencyKey = idempotencyKey,
                version = null,
                createdAt = now,
                expiresAt = now + HOLD_DURATION,
                status = BookingStatus.PENDING,
                cancelReason = null,
                paymentRequestedAt = null,
                confirmedAt = null,
                cancelledAt = null,
            )
        }

        private fun seatViolation(seats: List<BookedSeat>): BookingRuleException? = when {
            seats.isEmpty() -> NoSeatsException()
            seats.size > MAX_SEATS -> TooManySeatsException()
            seats.map { it.seatId }.toSet().size != seats.size -> DuplicateSeatException()
            else -> null
        }

        /** 영속 상태에서 복원한다. 규칙 검증은 하지 않는다. */
        fun restore(
            id: BookingId,
            memberId: MemberId,
            scheduleId: ScheduleId,
            seats: List<BookedSeat>,
            holdToken: HoldToken,
            idempotencyKey: String,
            version: Long,
            createdAt: Instant,
            expiresAt: Instant,
            status: BookingStatus,
            cancelReason: CancelReason?,
            paymentRequestedAt: Instant?,
            confirmedAt: Instant?,
            cancelledAt: Instant?,
        ): Booking = Booking(
            id, memberId, scheduleId, seats.toList(), holdToken, idempotencyKey, version, createdAt, expiresAt,
            status, cancelReason, paymentRequestedAt, confirmedAt, cancelledAt,
        )
    }
}
