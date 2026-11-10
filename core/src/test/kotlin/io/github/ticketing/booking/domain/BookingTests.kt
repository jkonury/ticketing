package io.github.ticketing.booking.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
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
        Booking.create(owner, ScheduleId(1), seats, HoldToken("hold-1"), t0)

    private fun codeOf(block: () -> Unit) = assertThrows<BookingRuleException>(block).code

    private fun confirmed() = booking().also { it.onPaymentSucceeded(beforeExpiry) }

    @Test
    fun `생성하면 PENDING이고 만료 시각은 5분 뒤이며 총액은 좌석 가격의 합이다`() {
        val b = booking(listOf(seat(1, 10_000), seat(2, 15_000)))
        assertEquals(BookingStatus.PENDING, b.status)
        assertEquals(t0.plusSeconds(300), b.expiresAt)
        assertEquals(BigDecimal(25_000), b.totalPrice)
        assertNull(b.id)
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
        val b = booking()
        assertFalse(b.isExpired(beforeExpiry))
        assertTrue(b.isExpired(atExpiry))
    }

    @Test
    fun `미만료 PENDING은 결제를 요청할 수 있고 재요청은 시각을 바꾸지 않는다`() {
        val b = booking()
        b.requestPayment(t0.plusSeconds(10))
        b.requestPayment(t0.plusSeconds(20))
        assertEquals(t0.plusSeconds(10), b.paymentRequestedAt)
        assertEquals(BookingStatus.PENDING, b.status)
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
    fun `미만료 PENDING에 결제 성공이 오면 CONFIRMED가 된다`() {
        val b = booking()
        assertEquals(PaymentOutcome.Confirmed, b.onPaymentSucceeded(beforeExpiry))
        assertEquals(BookingStatus.CONFIRMED, b.status)
        assertEquals(beforeExpiry, b.confirmedAt)
    }

    @Test
    fun `만료된 PENDING에 결제 성공이 오면 EXPIRED가 되고 환불이 필요하다`() {
        val b = booking()
        assertEquals(PaymentOutcome.RefundRequired(RefundReason.BOOKING_EXPIRED), b.onPaymentSucceeded(atExpiry))
        assertEquals(BookingStatus.EXPIRED, b.status)
    }

    @Test
    fun `결제 성공이 두 번 와도 상태가 다시 바뀌지 않는다`() {
        val b = booking()
        b.onPaymentSucceeded(beforeExpiry)
        assertEquals(PaymentOutcome.AlreadyConfirmed, b.onPaymentSucceeded(atExpiry.plusSeconds(60)))
        assertEquals(BookingStatus.CONFIRMED, b.status)
        assertEquals(beforeExpiry, b.confirmedAt)
    }

    @Test
    fun `EXPIRED에 결제 성공이 오면 환불이 필요하고 상태는 그대로다`() {
        val b = booking().also { it.expire(atExpiry) }
        assertEquals(PaymentOutcome.RefundRequired(RefundReason.BOOKING_EXPIRED), b.onPaymentSucceeded(atExpiry))
        assertEquals(BookingStatus.EXPIRED, b.status)
    }

    @Test
    fun `CANCELLED에 결제 성공이 오면 환불이 필요하고 상태는 그대로다`() {
        val b = booking().also { it.cancelByUser(owner, t0) }
        assertEquals(PaymentOutcome.RefundRequired(RefundReason.BOOKING_CANCELLED), b.onPaymentSucceeded(t0))
        assertEquals(BookingStatus.CANCELLED, b.status)
        assertEquals(CancelReason.USER_REQUESTED, b.cancelReason)
    }

    @Test
    fun `결제 실패가 오면 PENDING이 PAYMENT_FAILED 사유로 CANCELLED가 된다`() {
        val b = booking()
        b.onPaymentFailed(t0)
        assertEquals(BookingStatus.CANCELLED, b.status)
        assertEquals(CancelReason.PAYMENT_FAILED, b.cancelReason)
    }

    @Test
    fun `결제 실패가 PENDING이 아닌 예매에 오면 무시한다`() {
        val c = confirmed().also { it.onPaymentFailed(t0) }
        assertEquals(BookingStatus.CONFIRMED, c.status)
        val e = booking().also { it.expire(atExpiry); it.onPaymentFailed(atExpiry) }
        assertEquals(BookingStatus.EXPIRED, e.status)
        val x = booking().also { it.onPaymentFailed(t0); it.onPaymentFailed(t0) }
        assertEquals(CancelReason.PAYMENT_FAILED, x.cancelReason)
    }

    @Test
    fun `확정 실패는 PENDING을 SEAT_ALREADY_SOLD 사유로 CANCELLED로 바꾼다`() {
        val b = booking()
        b.failConfirmation(beforeExpiry)
        assertEquals(BookingStatus.CANCELLED, b.status)
        assertEquals(CancelReason.SEAT_ALREADY_SOLD, b.cancelReason)
    }

    @Test
    fun `PENDING이 아니면 확정 실패 처리를 할 수 없다`() {
        assertEquals(INVALID_BOOKING_STATE, codeOf { confirmed().failConfirmation(t0) })
    }

    @Test
    fun `만료 시각이 지난 PENDING은 만료 처리된다`() {
        val b = booking()
        b.expire(atExpiry)
        assertEquals(BookingStatus.EXPIRED, b.status)
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
        val b = booking()
        assertEquals(CancelOutcome.ReleasedHold, b.cancelByUser(owner, t0))
        assertEquals(BookingStatus.CANCELLED, b.status)
        assertEquals(CancelReason.USER_REQUESTED, b.cancelReason)
    }

    @Test
    fun `CONFIRMED를 본인이 취소하면 환불이 필요하다`() {
        val b = confirmed()
        assertEquals(CancelOutcome.RefundRequired, b.cancelByUser(owner, beforeExpiry))
        assertEquals(BookingStatus.CANCELLED, b.status)
        assertEquals(CancelReason.USER_REQUESTED, b.cancelReason)
    }

    @Test
    fun `본인 예매가 아니면 취소할 수 없다`() {
        assertEquals(NOT_BOOKING_OWNER, codeOf { booking().cancelByUser(MemberId(2), t0) })
    }

    @Test
    fun `EXPIRED와 CANCELLED는 취소할 수 없다`() {
        val e = booking().also { it.expire(atExpiry) }
        assertEquals(INVALID_BOOKING_STATE, codeOf { e.cancelByUser(owner, atExpiry) })
        val c = booking().also { it.cancelByUser(owner, t0) }
        assertEquals(INVALID_BOOKING_STATE, codeOf { c.cancelByUser(owner, t0) })
    }
}
