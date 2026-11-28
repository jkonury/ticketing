package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.application.port.provided.CreateBookingCommand
import io.github.ticketing.booking.application.port.provided.CreateBookingResult
import io.github.ticketing.booking.application.port.required.BookingCommandPort
import io.github.ticketing.booking.application.port.required.HoldResult
import io.github.ticketing.booking.application.port.required.ScheduleSeatCommandPort
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.BookingId
import io.github.ticketing.booking.domain.HoldToken
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.ScheduleCopy
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CreateBookingServiceTests {
    private val now = Instant.parse("2026-11-10T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val scheduleId = ScheduleId(1)

    private class FakeTransactionManager : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()
        override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
        override fun doCommit(status: DefaultTransactionStatus) = Unit
        override fun doRollback(status: DefaultTransactionStatus) = Unit
    }

    private class FakeBookings(var failOnSave: RuntimeException? = null) : BookingCommandPort {
        val stored = mutableListOf<Booking>()
        override fun save(booking: Booking): Booking {
            failOnSave?.let { throw it }
            if (findByMemberAndIdempotencyKey(booking.memberId, booking.idempotencyKey) != null) {
                throw DataIntegrityViolationException("uk_booking_member_idempotency_key")
            }
            val saved = Booking.restore(
                BookingId(stored.size + 1L), booking.memberId, booking.scheduleId, booking.seats, booking.holdToken,
                booking.idempotencyKey, 0, booking.createdAt, booking.expiresAt, booking.status, null, null, null, null,
            )
            stored += saved
            return saved
        }
        override fun findById(id: BookingId) = stored.firstOrNull { it.id == id }
        override fun findByMemberAndIdempotencyKey(memberId: MemberId, key: String) =
            stored.firstOrNull { it.memberId == memberId && it.idempotencyKey == key }
        override fun insertSoldSeats(bookingId: BookingId, scheduleId: ScheduleId, seatIds: List<SeatId>, now: Instant) = 0
        override fun deleteSoldSeats(bookingId: BookingId) = Unit
    }

    private class FakeScheduleSeats(private val schedules: Set<ScheduleId>, private val seats: List<ScheduleSeatCopy>) :
        ScheduleSeatCommandPort {
        override fun saveIfAbsent(schedule: ScheduleCopy, seats: List<ScheduleSeatCopy>) = false
        override fun findSchedule(scheduleId: ScheduleId): ScheduleCopy? = null
        override fun isSaleEnabled(scheduleId: ScheduleId) = true
        override fun updateSaleStatus(performanceId: Long, saleEnabled: Boolean, version: Long) = Unit
        override fun existsSchedule(scheduleId: ScheduleId) = scheduleId in schedules
        override fun findSeats(scheduleId: ScheduleId, seatIds: Collection<SeatId>) =
            seats.filter { it.scheduleId == scheduleId && it.seatId in seatIds }
    }

    private class FakeSeatHold(var result: HoldResult = HoldResult.Held) : SeatHoldPort {
        override fun reconcileSnapshot(scheduleId: ScheduleId, seatIds: List<SeatId>, soldSeatIds: Set<SeatId>, now: Instant) = 0

        val held = mutableListOf<HoldToken>()
        val released = mutableListOf<HoldToken>()
        override fun hold(
            scheduleId: ScheduleId,
            seatIds: List<SeatId>,
            owner: HoldToken,
            ttl: Duration,
            now: Instant,
            fingerprint: String?,
        ): HoldResult {
            if (result == HoldResult.Held) held += owner
            return result
        }
        override fun release(scheduleId: ScheduleId, seatIds: List<SeatId>, owner: HoldToken, now: Instant) {
            released += owner
        }
        override fun markSold(scheduleId: ScheduleId, seatIds: List<SeatId>, owner: HoldToken, now: Instant) = Unit
        override fun markAvailable(scheduleId: ScheduleId, seatIds: List<SeatId>, now: Instant) = Unit
        override fun initSnapshot(scheduleId: ScheduleId, seatIds: List<SeatId>) = Unit
    }

    private class ConcurrentHold : SeatHoldPort by FakeSeatHold() {
        private var owner: HoldToken? = null
        private var fingerprint: String? = null
        val busy = CountDownLatch(1)

        @Synchronized
        override fun hold(
            scheduleId: ScheduleId,
            seatIds: List<SeatId>,
            owner: HoldToken,
            ttl: Duration,
            now: Instant,
            fingerprint: String?,
        ): HoldResult {
            if (this.owner == null) {
                this.owner = owner
                this.fingerprint = fingerprint
                return HoldResult.Held
            }
            busy.countDown()
            return HoldResult.Unavailable(seatIds, fingerprint != null && fingerprint == this.fingerprint)
        }

        @Synchronized
        override fun release(scheduleId: ScheduleId, seatIds: List<SeatId>, owner: HoldToken, now: Instant) {
            if (this.owner == owner) this.owner = null
        }
    }

    private class BlockingBookings(private val failFirst: Boolean) : BookingCommandPort by FakeBookings() {
        private val delegate = FakeBookings()
        private val saves = AtomicInteger()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val emptyRead = CountDownLatch(1)

        override fun save(booking: Booking): Booking {
            if (saves.getAndIncrement() == 0) {
                entered.countDown()
                check(proceed.await(5, TimeUnit.SECONDS))
                if (failFirst) throw IllegalStateException("저장 실패")
            }
            return synchronized(delegate) { delegate.save(booking) }
        }

        override fun findByMemberAndIdempotencyKey(memberId: MemberId, key: String): Booking? =
            synchronized(delegate) { delegate.findByMemberAndIdempotencyKey(memberId, key) }.also {
                if (it == null) emptyRead.countDown()
            }
    }

    private val seatCopies = (1L..5L).map {
        ScheduleSeatCopy(scheduleId, SeatId(it), "R", "A$it", BigDecimal(100000), now.plusSeconds(3600))
    }
    private val scheduleSeats = FakeScheduleSeats(setOf(scheduleId), seatCopies)

    private fun service(bookings: BookingCommandPort, hold: SeatHoldPort, seats: ScheduleSeatCommandPort = scheduleSeats) =
        CreateBookingService(
            bookings,
            seats,
            hold,
            TransactionTemplate(FakeTransactionManager()),
            clock,
            io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
        )

    private fun command(vararg seatIds: Long, key: String = "key-1", schedule: Long = 1) =
        CreateBookingCommand(7, key, schedule, seatIds.toList())

    @Test
    fun `저장이 실패하면 선점을 해제하고 예외를 다시 던진다`() {
        val bookings = FakeBookings(failOnSave = IllegalStateException("db down"))
        val hold = FakeSeatHold()

        assertThatThrownBy { service(bookings, hold).create(command(1, 2)) }
            .isInstanceOf(IllegalStateException::class.java).hasMessage("db down")

        assertThat(hold.held).hasSize(1)
        assertThat(hold.released).isEqualTo(hold.held)
    }

    @Test
    fun `정상 생성하면 PENDING을 저장하고 좌석 라벨을 돌려준다`() {
        val bookings = FakeBookings()

        val result = service(bookings, FakeSeatHold()).create(command(1, 2))

        assertThat(result.created).isTrue()
        assertThat(result.booking.status).isEqualTo("PENDING")
        assertThat(result.booking.totalAmount).isEqualByComparingTo(BigDecimal(200000))
        assertThat(result.booking.seats.map { it.label }).containsExactly("A1", "A2")
        assertThat(result.booking.expiresAt).isEqualTo(now + Booking.HOLD_DURATION)
    }

    @Test
    fun `회차 시작부터 새 예매는 거절하고 기존 멱등 요청은 반환한다`() {
        val bookings = FakeBookings()
        val hold = FakeSeatHold()
        val original = service(bookings, hold).create(command(1))
        val closed = FakeScheduleSeats(setOf(scheduleId), seatCopies.map { it.copy(startsAt = now) })
        val service = service(bookings, hold, closed)

        assertThat(service.create(command(1)).booking.bookingId).isEqualTo(original.booking.bookingId)
        assertThatThrownBy { service.create(command(2, key = "new")) }
            .isInstanceOfSatisfying(BookingRuleViolation::class.java) { assertThat(it.code).isEqualTo("SALES_CLOSED") }
        assertThat(hold.held).hasSize(1)
    }

    @Test
    fun `자기 좌석이 아직 선점 중일 때 같은 멱등 키로 다시 요청하면 기존 예매를 돌려준다`() {
        val bookings = FakeBookings()
        val hold = FakeSeatHold()
        val service = service(bookings, hold)
        val first = service.create(command(1))
        hold.result = HoldResult.Unavailable(listOf(SeatId(1)))

        val second = service.create(command(1))

        assertThat(second.created).isFalse()
        assertThat(second.booking.bookingId).isEqualTo(first.booking.bookingId)
        assertThat(hold.held).hasSize(1)
        assertThat(bookings.stored).hasSize(1)
    }

    @Test
    fun `첫 저장이 끝나기 전 동일 요청은 커밋된 예매를 돌려준다`() {
        val bookings = BlockingBookings(failFirst = false)
        val hold = ConcurrentHold()
        val service = service(bookings, hold)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val first = executor.submit<CreateBookingResult> { service.create(command(1)) }
            assertThat(bookings.entered.await(5, TimeUnit.SECONDS)).isTrue()
            val second = executor.submit<CreateBookingResult> { service.create(command(1)) }
            assertThat(hold.busy.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(bookings.emptyRead.await(5, TimeUnit.SECONDS)).isTrue()
            assertThatThrownBy { service.create(command(1, key = "other")) }.isInstanceOf(SeatAlreadyHeldException::class.java)
            assertThatThrownBy { service.create(CreateBookingCommand(8, "key-1", 1, listOf(1))) }
                .isInstanceOf(SeatAlreadyHeldException::class.java)
            bookings.proceed.countDown()
            val original = first.get(5, TimeUnit.SECONDS)
            val replay = second.get(5, TimeUnit.SECONDS)
            assertThat(replay.created).isFalse()
            assertThat(replay.booking.bookingId).isEqualTo(original.booking.bookingId)
        }
    }

    @Test
    fun `첫 저장이 실패해 선점을 풀면 동일 요청이 다시 선점해 생성한다`() {
        val bookings = BlockingBookings(failFirst = true)
        val hold = ConcurrentHold()
        val service = service(bookings, hold)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val first = executor.submit<CreateBookingResult> { service.create(command(1)) }
            assertThat(bookings.entered.await(5, TimeUnit.SECONDS)).isTrue()
            val second = executor.submit<CreateBookingResult> { service.create(command(1)) }
            assertThat(hold.busy.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(bookings.emptyRead.await(5, TimeUnit.SECONDS)).isTrue()
            bookings.proceed.countDown()
            assertThatThrownBy { first.get(5, TimeUnit.SECONDS) }.hasCauseInstanceOf(IllegalStateException::class.java)
            assertThat(second.get(5, TimeUnit.SECONDS).created).isTrue()
        }
    }

    @Test
    fun `좌석이 풀린 뒤 같은 멱등 키로 다시 요청하면 선점을 해제하고 기존 예매를 돌려준다`() {
        val bookings = FakeBookings()
        val hold = FakeSeatHold()
        val service = service(bookings, hold)
        val first = service.create(command(1))

        val second = service.create(command(1))

        assertThat(second.created).isFalse()
        assertThat(second.booking.bookingId).isEqualTo(first.booking.bookingId)
        assertThat(hold.held).hasSize(2)
        assertThat(hold.released).containsExactly(hold.held[1])
        assertThat(bookings.stored).hasSize(1)
    }

    @Test
    fun `선점에 실패하면 SEAT_ALREADY_HELD이고 아무것도 저장하지 않는다`() {
        val bookings = FakeBookings()
        val hold = FakeSeatHold(HoldResult.Unavailable(listOf(SeatId(2))))

        assertThatThrownBy { service(bookings, hold).create(command(1, 2)) }
            .isInstanceOfSatisfying(SeatAlreadyHeldException::class.java) {
                assertThat(it.code).isEqualTo("SEAT_ALREADY_HELD")
                assertThat(it.properties["seatIds"]).isEqualTo(listOf(2L))
            }

        assertThat(bookings.stored).isEmpty()
    }

    @Test
    fun `다른 요청이 좌석을 먼저 선점했으면 409이고 승자의 예매는 그대로다`() {
        val bookings = FakeBookings()
        val hold = FakeSeatHold()
        val winner = service(bookings, hold).create(command(1, key = "winner"))
        hold.result = HoldResult.Unavailable(listOf(SeatId(1)))

        assertThatThrownBy { service(bookings, hold).create(command(1, key = "loser")) }
            .isInstanceOf(SeatAlreadyHeldException::class.java)

        assertThat(bookings.stored.map { it.idempotencyKey }).containsExactly("winner")
        assertThat(winner.created).isTrue()
    }

    @Test
    fun `없는 좌석이나 회차면 404이고 선점하지 않는다`() {
        val hold = FakeSeatHold()

        assertThatThrownBy { service(FakeBookings(), hold).create(command(1, 99)) }
            .isInstanceOf(SeatNotFoundException::class.java)
        assertThatThrownBy { service(FakeBookings(), hold).create(command(1, schedule = 42)) }
            .isInstanceOf(ScheduleNotFoundException::class.java)

        assertThat(hold.held).isEmpty()
    }

    @Test
    fun `5매는 도메인 규칙으로 400 예외가 되고 선점하지 않는다`() {
        val hold = FakeSeatHold()

        assertThatThrownBy { service(FakeBookings(), hold).create(command(1, 2, 3, 4, 5)) }
            .isInstanceOfSatisfying(io.github.ticketing.DomainException::class.java) {
                assertThat(it.code).isEqualTo("TOO_MANY_SEATS")
                assertThat(it.status.value()).isEqualTo(400)
            }

        assertThat(hold.held).isEmpty()
    }
}
