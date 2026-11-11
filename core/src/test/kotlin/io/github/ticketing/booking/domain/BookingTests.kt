package io.github.ticketing.booking.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookingTests {

    private val t0 = Instant.parse("2026-11-10T10:00:00Z")
    private val beforeExpiry = t0.plusSeconds(299)
    private val atExpiry = t0.plusSeconds(300)
    private val owner = MemberId(1)

    private fun seat(id: Long, price: Long = 10_000) = BookedSeat(SeatId(id), BigDecimal(price))

    private fun booking(seats: List<BookedSeat> = listOf(seat(1))) =
        Booking.create(owner, ScheduleId(1), seats, HoldToken("hold-1"), t0, "key-1")

    private fun codeOf(block: () -> Unit) = assertThrows<BookingRuleException>(block).code

    private val price = BigDecimal(10_000)

    private fun confirmed() = booking().also {
        it.onPaymentSucceeded(beforeExpiry, price)
        it.confirm(beforeExpiry)
    }

    @Test
    fun `생성하면 PENDING이고 만료 시각은 5분 뒤이며 총액은 좌석 가격의 합이다`() {
        val createdBooking = booking(listOf(seat(1, 10_000), seat(2, 15_000)))

        assertEquals(BookingStatus.PENDING, createdBooking.status)
        assertEquals(t0.plusSeconds(300), createdBooking.expiresAt)
        assertEquals(BigDecimal(25_000), createdBooking.totalPrice)
        assertNull(createdBooking.id)
    }

    @Test
    fun `좌석이 4개면 생성된다`() {
        assertEquals(4, booking((1L..4L).map { seat(it) }).seats.size)
    }

    @Test
    fun `좌석이 5개면 TOO_MANY_SEATS`() {
        assertEquals(TOO_MANY_SEATS, codeOf { booking((1L..5L).map { seat(it) }) })
    }

    @Test
    fun `좌석이 없으면 NO_SEATS`() {
        assertEquals(NO_SEATS, codeOf { booking(emptyList()) })
    }

    @Test
    fun `중복 좌석이면 DUPLICATE_SEAT`() {
        assertEquals(DUPLICATE_SEAT, codeOf { booking(listOf(seat(1), seat(1))) })
    }

    @Test
    fun `만료 시각 정각부터 만료로 판정한다`() {
        val pendingBooking = booking()

        assertFalse(pendingBooking.isExpired(beforeExpiry))
        assertTrue(pendingBooking.isExpired(atExpiry))
    }

    @Test
    fun `일괄 만료는 미요청 예매의 만료 시각과 결제 요청 예매의 유예 경계를 지킨다`() {
        val grace = Duration.ofMinutes(30)
        val pending = booking()
        val requested = booking().also { it.requestPayment(t0) }

        assertFalse(pending.isSweepable(beforeExpiry, grace))
        assertThrows<InvalidBookingStateException> { pending.expireBySweep(beforeExpiry, grace) }
        assertTrue(pending.isSweepable(atExpiry, grace))
        assertFalse(requested.isSweepable(atExpiry.plus(grace).minusSeconds(1), grace))
        assertThrows<InvalidBookingStateException> { requested.expireBySweep(atExpiry, grace) }
        assertTrue(requested.isSweepable(atExpiry.plus(grace), grace))
        pending.expireBySweep(atExpiry, grace)
        requested.expireBySweep(atExpiry.plus(grace), grace)
        assertEquals(BookingStatus.EXPIRED, pending.status)
        assertEquals(BookingStatus.EXPIRED, requested.status)
    }

    @Test
    fun `일괄 만료는 PENDING이 아닌 예매를 변경하지 않는다`() {
        val grace = Duration.ofMinutes(30)
        val bookings = listOf(
            confirmed(),
            booking().also { it.cancelByUser(owner, t0) },
            booking().also { it.expire(atExpiry) },
        )

        bookings.forEach {
            assertFalse(it.isSweepable(atExpiry.plus(grace), grace))
            assertThrows<InvalidBookingStateException> { it.expireBySweep(atExpiry.plus(grace), grace) }
        }
    }

    @Test
    fun `미만료 PENDING은 결제를 요청할 수 있고 재요청은 시각을 바꾸지 않는다`() {
        val pendingBooking = booking()

        pendingBooking.requestPayment(t0.plusSeconds(10))
        pendingBooking.requestPayment(t0.plusSeconds(20))

        assertEquals(t0.plusSeconds(10), pendingBooking.paymentRequestedAt)
        assertEquals(BookingStatus.PENDING, pendingBooking.status)
    }

    @Test
    fun `만료된 PENDING은 결제를 요청할 수 없다`() {
        assertEquals(BOOKING_EXPIRED, codeOf { booking().requestPayment(atExpiry) })
    }

    @Test
    fun `PENDING이 아니면 결제를 요청할 수 없다`() {
        assertEquals(INVALID_BOOKING_STATE, codeOf { confirmed().requestPayment(beforeExpiry) })
    }

    @Test
    fun `미만료 PENDING에 금액이 맞는 결제 성공이 오면 확정 대상이 되고 confirm하면 CONFIRMED가 된다`() {
        val pendingBooking = booking()

        val outcome = pendingBooking.onPaymentSucceeded(beforeExpiry, price)

        assertEquals(PaymentOutcome.ConfirmationRequired, outcome)
        assertEquals(BookingStatus.PENDING, pendingBooking.status)

        pendingBooking.confirm(beforeExpiry)

        assertEquals(BookingStatus.CONFIRMED, pendingBooking.status)
        assertEquals(beforeExpiry, pendingBooking.confirmedAt)
    }

    @Test
    fun `만료된 PENDING에 결제 성공이 오면 EXPIRED가 되고 환불이 필요하다`() {
        val pendingBooking = booking()

        val outcome = pendingBooking.onPaymentSucceeded(atExpiry, price)

        assertEquals(PaymentOutcome.RefundRequired(RefundReason.BOOKING_EXPIRED), outcome)
        assertEquals(BookingStatus.EXPIRED, pendingBooking.status)
    }

    @Test
    fun `미만료 PENDING에 금액이 다른 결제 성공이 오면 AMOUNT_MISMATCH로 CANCELLED가 된다`() {
        val pendingBooking = booking()

        val outcome = pendingBooking.onPaymentSucceeded(beforeExpiry, BigDecimal(9_999))

        assertEquals(PaymentOutcome.AmountMismatch, outcome)
        assertEquals(BookingStatus.CANCELLED, pendingBooking.status)
        assertEquals(CancelReason.AMOUNT_MISMATCH, pendingBooking.cancelReason)
        assertEquals(beforeExpiry, pendingBooking.cancelledAt)
        assertNull(pendingBooking.confirmedAt)
    }

    @Test
    fun `금액은 값이 같으면 스케일이 달라도 일치한다`() {
        val outcome = booking().onPaymentSucceeded(beforeExpiry, BigDecimal("10000.00"))

        assertEquals(PaymentOutcome.ConfirmationRequired, outcome)
    }

    @Test
    fun `만료된 PENDING은 금액이 달라도 만료가 먼저 판정된다`() {
        val pendingBooking = booking()

        val outcome = pendingBooking.onPaymentSucceeded(atExpiry, BigDecimal(1))

        assertEquals(PaymentOutcome.RefundRequired(RefundReason.BOOKING_EXPIRED), outcome)
        assertEquals(BookingStatus.EXPIRED, pendingBooking.status)
    }

    @Test
    fun `PENDING이 아니면 confirm할 수 없다`() {
        assertEquals(INVALID_BOOKING_STATE, codeOf { confirmed().confirm(t0) })
    }

    @Test
    fun `결제 성공이 두 번 와도 상태가 다시 바뀌지 않는다`() {
        val pendingBooking = booking()

        pendingBooking.onPaymentSucceeded(beforeExpiry, price)
        pendingBooking.confirm(beforeExpiry)
        val outcome = pendingBooking.onPaymentSucceeded(atExpiry.plusSeconds(60), price)

        assertEquals(PaymentOutcome.AlreadyConfirmed, outcome)
        assertEquals(BookingStatus.CONFIRMED, pendingBooking.status)
        assertEquals(beforeExpiry, pendingBooking.confirmedAt)
    }

    @Test
    fun `EXPIRED에 결제 성공이 오면 환불이 필요하고 상태는 그대로다`() {
        val expiredBooking = booking().also { it.expire(atExpiry) }

        val outcome = expiredBooking.onPaymentSucceeded(atExpiry, price)

        assertEquals(PaymentOutcome.RefundRequired(RefundReason.BOOKING_EXPIRED), outcome)
        assertEquals(BookingStatus.EXPIRED, expiredBooking.status)
    }

    @Test
    fun `CANCELLED에 결제 성공이 오면 환불이 필요하고 상태는 그대로다`() {
        val cancelledBooking = booking().also { it.cancelByUser(owner, t0) }

        val outcome = cancelledBooking.onPaymentSucceeded(t0, price)

        assertEquals(PaymentOutcome.RefundRequired(RefundReason.BOOKING_CANCELLED), outcome)
        assertEquals(BookingStatus.CANCELLED, cancelledBooking.status)
        assertEquals(CancelReason.USER_REQUESTED, cancelledBooking.cancelReason)
    }

    @Test
    fun `결제 실패가 오면 PENDING이 PAYMENT_FAILED 사유로 CANCELLED가 된다`() {
        val pendingBooking = booking()

        pendingBooking.onPaymentFailed(t0)

        assertEquals(BookingStatus.CANCELLED, pendingBooking.status)
        assertEquals(CancelReason.PAYMENT_FAILED, pendingBooking.cancelReason)
    }

    @Test
    fun `결제 실패가 PENDING이 아닌 예매에 오면 무시한다`() {
        val confirmedBooking = confirmed()

        confirmedBooking.onPaymentFailed(t0)

        assertEquals(BookingStatus.CONFIRMED, confirmedBooking.status)

        val expiredBooking = booking()
        expiredBooking.expire(atExpiry)

        expiredBooking.onPaymentFailed(atExpiry)

        assertEquals(BookingStatus.EXPIRED, expiredBooking.status)

        val failedTwiceBooking = booking()

        failedTwiceBooking.onPaymentFailed(t0)
        failedTwiceBooking.onPaymentFailed(t0)

        assertEquals(CancelReason.PAYMENT_FAILED, failedTwiceBooking.cancelReason)
    }

    @Test
    fun `확정 실패는 PENDING을 SEAT_ALREADY_SOLD 사유로 CANCELLED로 바꾼다`() {
        val pendingBooking = booking()

        pendingBooking.failConfirmation(beforeExpiry)

        assertEquals(BookingStatus.CANCELLED, pendingBooking.status)
        assertEquals(CancelReason.SEAT_ALREADY_SOLD, pendingBooking.cancelReason)
    }

    @Test
    fun `PENDING이 아니면 확정 실패 처리를 할 수 없다`() {
        assertEquals(INVALID_BOOKING_STATE, codeOf { confirmed().failConfirmation(t0) })
    }

    @Test
    fun `만료 시각이 지난 PENDING은 만료 처리된다`() {
        val pendingBooking = booking()

        pendingBooking.expire(atExpiry)

        assertEquals(BookingStatus.EXPIRED, pendingBooking.status)
    }

    @Test
    fun `만료 시각 전에는 만료 처리할 수 없다`() {
        assertEquals(INVALID_BOOKING_STATE, codeOf { booking().expire(beforeExpiry) })
    }

    @Test
    fun `PENDING이 아니면 만료 처리할 수 없다`() {
        assertEquals(INVALID_BOOKING_STATE, codeOf { confirmed().expire(atExpiry) })
    }

    @Test
    fun `PENDING을 본인이 취소하면 선점 해제 결과와 USER_REQUESTED 사유를 얻는다`() {
        val pendingBooking = booking()

        val outcome = pendingBooking.cancelByUser(owner, t0)

        assertEquals(CancelOutcome.ReleasedHold, outcome)
        assertEquals(BookingStatus.CANCELLED, pendingBooking.status)
        assertEquals(CancelReason.USER_REQUESTED, pendingBooking.cancelReason)
    }

    @Test
    fun `CONFIRMED를 본인이 취소하면 환불이 필요하다`() {
        val confirmedBooking = confirmed()

        val outcome = confirmedBooking.cancelByUser(owner, beforeExpiry)

        assertEquals(CancelOutcome.RefundRequired, outcome)
        assertEquals(BookingStatus.CANCELLED, confirmedBooking.status)
        assertEquals(CancelReason.USER_REQUESTED, confirmedBooking.cancelReason)
    }

    @Test
    fun `본인 예매가 아니면 취소할 수 없다`() {
        assertEquals(NOT_BOOKING_OWNER, codeOf { booking().cancelByUser(MemberId(2), t0) })
    }

    @Test
    fun `EXPIRED와 CANCELLED는 취소할 수 없다`() {
        val expiredBooking = booking().also { it.expire(atExpiry) }

        assertEquals(INVALID_BOOKING_STATE, codeOf { expiredBooking.cancelByUser(owner, atExpiry) })

        val cancelledBooking = booking().also { it.cancelByUser(owner, t0) }

        assertEquals(INVALID_BOOKING_STATE, codeOf { cancelledBooking.cancelByUser(owner, t0) })
    }
}
