package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
import io.github.ticketing.booking.BookingExpired
import io.github.ticketing.booking.application.port.provided.BookingDetailView
import io.github.ticketing.booking.application.port.provided.BookingPage
import io.github.ticketing.booking.application.port.provided.BookingQueryUseCase
import io.github.ticketing.booking.application.port.provided.ExpireResult
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.required.BookingCommandPort
import io.github.ticketing.booking.application.port.required.BookingQueryPort
import io.github.ticketing.booking.application.port.required.HoldResult
import io.github.ticketing.booking.application.port.required.ScheduleSeatCommandPort
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.domain.BookedSeat
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.BookingId
import io.github.ticketing.booking.domain.BookingStatus
import io.github.ticketing.booking.domain.CancelReason
import io.github.ticketing.booking.domain.HoldToken
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.ScheduleCopy
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId
import io.github.ticketing.payment.PaymentRequested
import io.github.ticketing.payment.RefundRequested
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class BookingServicesTests {
    private val now = Instant.parse("2026-11-10T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val scheduleId = ScheduleId(1)
    private val seatId = SeatId(2)
    private val price = BigDecimal("10000")
    private val booking = Booking.restore(
        BookingId(3), MemberId(4), scheduleId, listOf(BookedSeat(seatId, price)), HoldToken("hold"),
        "key", 0, now, now.plusSeconds(300), BookingStatus.PENDING, null, null, null, null,
    )
    private val bookings = FakeBookings(booking)
    private val seats = FakeScheduleSeats()
    private val holds = FakeHolds()
    private val events = Events()
    private val manager = FakeTransactionManager()
    private val tx = TransactionTemplate(manager)

    private class FakeTransactionManager : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()
        override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
        override fun doCommit(status: DefaultTransactionStatus) = Unit
        override fun doRollback(status: DefaultTransactionStatus) = Unit
    }

    private class Events : ApplicationEventPublisher {
        val published = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            published += event
        }
    }

    private class FakeBookings(private val booking: Booking) : BookingCommandPort {
        var saves = 0
        var inserted = 1
        var deleted = 0
        override fun save(booking: Booking): Booking {
            saves++
            return booking
        }
        override fun findById(id: BookingId) = booking.takeIf { it.id == id }
        override fun findByMemberAndIdempotencyKey(memberId: MemberId, key: String) =
            booking.takeIf { it.memberId == memberId && it.idempotencyKey == key }
        override fun insertSoldSeats(bookingId: BookingId, scheduleId: ScheduleId, seatIds: List<SeatId>, now: Instant) = inserted
        override fun deleteSoldSeats(bookingId: BookingId) {
            deleted++
        }
    }

    private inner class FakeScheduleSeats : ScheduleSeatCommandPort {
        var saved = false
        override fun saveIfAbsent(schedule: ScheduleCopy, seats: List<ScheduleSeatCopy>): Boolean {
            if (saved) return false
            saved = true
            return true
        }
        override fun findSchedule(scheduleId: ScheduleId) = ScheduleCopy(scheduleId, 10, "공연", 11, now.plusSeconds(3600), now)
        override fun existsSchedule(scheduleId: ScheduleId) = true
        override fun findSeats(scheduleId: ScheduleId, seatIds: Collection<SeatId>) =
            seatIds.map { ScheduleSeatCopy(scheduleId, it, "R", "A1", price) }
    }

    private class FakeHolds : SeatHoldPort {
        val actions = mutableListOf<String>()
        var failRelease = false
        val reconciled = mutableListOf<Pair<List<SeatId>, Set<SeatId>>>()
        override fun hold(scheduleId: ScheduleId, seatIds: List<SeatId>, owner: HoldToken, ttl: Duration, now: Instant) = HoldResult.Held
        override fun release(scheduleId: ScheduleId, seatIds: List<SeatId>, owner: HoldToken, now: Instant) {
            actions += "release"
            if (failRelease) throw IllegalStateException("release failed")
        }
        override fun markSold(scheduleId: ScheduleId, seatIds: List<SeatId>, owner: HoldToken, now: Instant) {
            actions += "sold"
        }
        override fun markAvailable(scheduleId: ScheduleId, seatIds: List<SeatId>, now: Instant) {
            actions += "available"
        }
        override fun initSnapshot(scheduleId: ScheduleId, seatIds: List<SeatId>) {
            actions += "init"
        }
        override fun reconcileSnapshot(scheduleId: ScheduleId, seatIds: List<SeatId>, soldSeatIds: Set<SeatId>, now: Instant): Int {
            reconciled += seatIds to soldSeatIds
            return seatIds.size
        }
    }

    private fun payment(clock: Clock = this.clock) = PaymentResultService(bookings, holds, seats, events, tx, clock)
    private fun request(clock: Clock = this.clock) = RequestPaymentService(bookings, holds, events, tx, clock)
    private fun cancel() = CancelBookingService(bookings, seats, holds, events, tx, clock)
    private fun later(seconds: Long) = Clock.fixed(now.plusSeconds(seconds), ZoneOffset.UTC)

    @Test
    fun `결제 요청은 한 번 발행하고 만료 이후 재요청도 중복 발행하지 않는다`() {
        request().request(4, 3)
        request(later(301)).request(4, 3)

        assertThat(events.published.filterIsInstance<PaymentRequested>()).hasSize(1)
        assertThat(bookings.saves).isEqualTo(1)
        assertThat(booking.paymentRequestedAt).isEqualTo(now)
    }

    @Test
    fun `만료된 결제 요청은 만료 이벤트와 선점 해제 후 예외를 던진다`() {
        assertThatThrownBy { request(later(300)).request(4, 3) }.isInstanceOf(io.github.ticketing.DomainException::class.java)

        assertThat(booking.status).isEqualTo(BookingStatus.EXPIRED)
        assertThat(events.published.filterIsInstance<BookingExpired>()).hasSize(1)
        assertThat(holds.actions).containsExactly("release")
    }

    @Test
    fun `남의 예매와 없는 예매는 결제를 요청할 수 없다`() {
        assertThatThrownBy { request().request(5, 3) }.isInstanceOf(io.github.ticketing.DomainException::class.java)
        assertThatThrownBy { request().request(4, 99) }.isInstanceOf(BookingNotFoundException::class.java)

        assertThat(events.published).isEmpty()
        assertThat(bookings.saves).isZero()
    }

    @Test
    fun `결제 성공은 확정과 좌석 판매를 수행하고 재수신은 무시한다`() {
        payment().onPaymentSucceeded(8, 3, price)
        payment().onPaymentSucceeded(8, 3, price)

        assertThat(booking.status).isEqualTo(BookingStatus.CONFIRMED)
        assertThat(events.published.filterIsInstance<BookingConfirmed>()).hasSize(1)
        assertThat(holds.actions).containsExactly("sold")
    }

    @Test
    fun `좌석 판매 충돌은 판매 기록을 되돌리고 취소와 환불을 요청한다`() {
        bookings.inserted = 0

        payment().onPaymentSucceeded(8, 3, price)

        assertThat(booking.cancelReason).isEqualTo(CancelReason.SEAT_ALREADY_SOLD)
        assertThat(bookings.deleted).isEqualTo(1)
        assertThat(events.published.filterIsInstance<RefundRequested>().single().reason).isEqualTo("SEAT_ALREADY_SOLD")
        assertThat(events.published.filterIsInstance<BookingCancelled>()).hasSize(1)
        assertThat(holds.actions).containsExactly("release")
    }

    @Test
    fun `결제 금액 불일치는 취소와 환불을 요청한다`() {
        payment().onPaymentSucceeded(8, 3, BigDecimal.ONE)

        assertThat(booking.cancelReason).isEqualTo(CancelReason.AMOUNT_MISMATCH)
        assertThat(events.published.filterIsInstance<RefundRequested>().single().reason).isEqualTo("AMOUNT_MISMATCH")
        assertThat(holds.actions).containsExactly("release")
    }

    @Test
    fun `만료 후 결제 성공은 만료와 환불을 발행한다`() {
        payment(later(300)).onPaymentSucceeded(8, 3, price)

        assertThat(booking.status).isEqualTo(BookingStatus.EXPIRED)
        assertThat(events.published.filterIsInstance<BookingExpired>()).hasSize(1)
        assertThat(events.published.filterIsInstance<RefundRequested>()).hasSize(1)
        assertThat(holds.actions).containsExactly("release")
    }

    @Test
    fun `결제 실패는 취소하고 재수신은 무시한다`() {
        payment().onPaymentFailed(8, 3, "실패")
        payment().onPaymentFailed(8, 3, "실패")

        assertThat(booking.cancelReason).isEqualTo(CancelReason.PAYMENT_FAILED)
        assertThat(events.published.filterIsInstance<BookingCancelled>()).hasSize(1)
        assertThat(holds.actions).containsExactly("release")
    }

    @Test
    fun `대기 예매 취소와 재취소는 한 번만 선점을 해제한다`() {
        cancel().cancel(4, 3)
        cancel().cancel(4, 3)

        assertThat(booking.status).isEqualTo(BookingStatus.CANCELLED)
        assertThat(events.published.filterIsInstance<BookingCancelled>()).hasSize(1)
        assertThat(holds.actions).containsExactly("release")
    }

    @Test
    fun `확정 예매 취소는 판매 기록을 지우고 환불과 좌석 반환을 요청한다`() {
        booking.confirm(now)

        cancel().cancel(4, 3)

        assertThat(bookings.deleted).isEqualTo(1)
        assertThat(events.published.filterIsInstance<RefundRequested>()).hasSize(1)
        assertThat(holds.actions).containsExactly("available")
    }

    @Test
    fun `남의 예매 취소는 거부한다`() {
        assertThatThrownBy { cancel().cancel(5, 3) }.isInstanceOf(io.github.ticketing.DomainException::class.java)

        assertThat(events.published).isEmpty()
        assertThat(holds.actions).isEmpty()
    }

    @Test
    fun `일괄 만료는 대상만 처리하고 재실행은 건너뛴다`() {
        val service = ExpireBookingService(bookings, holds, events, manager, later(300), Duration.ofMinutes(30))

        assertThat(service.expireIfDue(3)).isEqualTo(ExpireResult.Expired)
        assertThat(service.expireIfDue(3)).isInstanceOf(ExpireResult.Skipped::class.java)
        assertThat(service.expireIfDue(99)).isInstanceOf(ExpireResult.Skipped::class.java)
        assertThat(events.published.filterIsInstance<BookingExpired>()).hasSize(1)
        assertThat(holds.actions).containsExactly("release")
    }

    @Test
    fun `회차 등록은 최초 저장 후 커밋 시 스냅샷을 만들고 재등록은 건너뛴다`() {
        val service = RegisterScheduleSeatsService(seats, holds, clock)
        val command = RegisterScheduleSeatsCommand(1, 10, "공연", 11, now, listOf(RegisterScheduleSeatsCommand.SeatInfo(2, "R", "A1", price)))
        TransactionSynchronizationManager.initSynchronization()
        try {
            assertThat(service.register(command)).isTrue()
            assertThat(service.register(command)).isFalse()
            assertThat(holds.actions).isEmpty()
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
            assertThat(holds.actions).containsExactly("init")
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `선점 해제 실패는 이미 처리한 결제 실패를 되돌리지 않는다`() {
        holds.failRelease = true

        payment().onPaymentFailed(8, 3, "실패")

        assertThat(booking.status).isEqualTo(BookingStatus.CANCELLED)
        assertThat(events.published.filterIsInstance<BookingCancelled>()).hasSize(1)
        assertThat(holds.actions).containsExactly("release")
    }

    @Test
    fun `결제 요청한 예매는 유예 시간이 끝날 때까지 일괄 만료하지 않는다`() {
        booking.requestPayment(now)
        val beforeGrace = ExpireBookingService(bookings, holds, events, manager, later(2099), Duration.ofMinutes(30))
        val atGrace = ExpireBookingService(bookings, holds, events, manager, later(2100), Duration.ofMinutes(30))

        assertThat(beforeGrace.expireIfDue(3)).isInstanceOf(ExpireResult.Skipped::class.java)
        assertThat(atGrace.expireIfDue(3)).isEqualTo(ExpireResult.Expired)
        assertThat(holds.actions).containsExactly("release")
    }

    @Test
    fun `확정 또는 취소된 예매에는 새 결제를 요청할 수 없다`() {
        booking.confirm(now)

        assertThatThrownBy { request().request(4, 3) }.isInstanceOf(io.github.ticketing.DomainException::class.java)
        cancel().cancel(4, 3)
        assertThatThrownBy { request().request(4, 3) }.isInstanceOf(io.github.ticketing.DomainException::class.java)
        assertThat(events.published.filterIsInstance<PaymentRequested>()).isEmpty()
    }

    private class FakeQueries : BookingQueryPort {
        var requestedSize = 0
        var detail: BookingDetailView? = null
        var soldReads = 0
        override fun findUpcomingScheduleIds(now: Instant, afterId: Long?, limit: Int) = listOf(1L)
        override fun findSeatIds(scheduleId: Long) = listOf(2L, 3L)
        override fun findSoldSeatIds(scheduleId: Long): Set<Long> {
            soldReads++
            return if (soldReads == 1) setOf(2L) else setOf(2L, 3L)
        }
        override fun findSweepCandidateIds(cutoff: Instant, paymentCutoff: Instant, afterId: Long?, limit: Int) = emptyList<Long>()
        override fun findMyBookings(memberId: Long, cursor: Long?, size: Int): BookingPage {
            requestedSize = size
            return BookingPage(emptyList(), cursor)
        }
        override fun findDetail(bookingId: Long) = detail
    }

    @Test
    fun `조회는 페이지 크기를 제한하고 남의 상세를 숨긴다`() {
        val queries = FakeQueries()
        queries.detail = BookingDetailView(3, 4, 1, "PENDING", "공연", now, price, now, now, emptyList())
        val service = BookingQueryService(queries)

        service.myBookings(4, null, 0)
        assertThat(queries.requestedSize).isEqualTo(1)
        service.myBookings(4, null, Int.MAX_VALUE)
        assertThat(queries.requestedSize).isEqualTo(BookingQueryUseCase.MAX_PAGE_SIZE)
        assertThat(service.detail(4, 3)).isEqualTo(queries.detail)
        assertThatThrownBy { service.detail(5, 3) }.isInstanceOf(BookingNotFoundException::class.java)
        queries.detail = null
        assertThatThrownBy { service.detail(4, 99) }.isInstanceOf(BookingNotFoundException::class.java)
    }

    @Test
    fun `스냅샷 보정 도중 새로 판매된 좌석도 다시 보정한다`() {
        val queries = FakeQueries()
        val service = ReconcileSeatSnapshotService(SeatSnapshotQueries(queries), holds, clock)

        val changed = service.reconcile(1)

        assertThat(changed).isEqualTo(3)
        assertThat(holds.reconciled).containsExactly(
            listOf(SeatId(2), SeatId(3)) to setOf(SeatId(2)),
            listOf(SeatId(3)) to setOf(SeatId(3)),
        )
    }
}
