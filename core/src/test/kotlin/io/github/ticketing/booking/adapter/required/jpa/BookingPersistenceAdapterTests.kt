package io.github.ticketing.booking.adapter.required.jpa

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.booking.application.port.required.BookingCommandPort
import io.github.ticketing.booking.domain.BookedSeat
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.BookingId
import io.github.ticketing.booking.domain.BookingStatus
import io.github.ticketing.booking.domain.HoldToken
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class BookingPersistenceAdapterTests : IntegrationTestSupport() {

    @Autowired
    lateinit var port: BookingCommandPort

    @Autowired
    lateinit var tx: TransactionTemplate

    private val now = Instant.parse("2026-11-10T10:00:00Z")
    private val ids = AtomicLong(System.nanoTime() % 1_000_000_000L * 100)

    private fun newBooking(
        member: Long = ids.incrementAndGet(),
        schedule: Long = ids.incrementAndGet(),
        key: String = UUID.randomUUID().toString(),
        vararg seats: Long,
    ) = Booking.create(
        MemberId(member),
        ScheduleId(schedule),
        (seats.takeIf { it.isNotEmpty() } ?: longArrayOf(1, 2)).map { BookedSeat(SeatId(it), BigDecimal(10_000)) },
        HoldToken(UUID.randomUUID().toString()),
        now,
        key,
    )

    @Test
    fun `저장하고 조회하면 좌석과 상태가 그대로 복원된다`() {
        val saved = tx.execute { port.save(newBooking()) }!!

        val found = tx.execute { port.findById(saved.id!!) }!!

        assertThat(found.id).isEqualTo(saved.id)
        assertThat(found.seats.map { it.seatId.value }).containsExactly(1L, 2L)
        assertThat(found.totalPrice).isEqualByComparingTo("20000")
        assertThat(found.status).isEqualTo(BookingStatus.PENDING)
        assertThat(found.expiresAt).isEqualTo(now.plusSeconds(300))
        assertThat(found.idempotencyKey).isEqualTo(saved.idempotencyKey)
    }

    @Test
    fun `상태 전이를 저장하면 버전이 오르고 취소 시각이 남는다`() {
        val saved = tx.execute { port.save(newBooking()) }!!
        val loaded = tx.execute { port.findById(saved.id!!) }!!

        loaded.cancelByUser(loaded.memberId, now.plusSeconds(10))
        val updated = tx.execute { port.save(loaded) }!!

        assertThat(updated.version).isEqualTo(saved.version!! + 1)

        val found = tx.execute { port.findById(saved.id!!) }!!

        assertThat(found.status).isEqualTo(BookingStatus.CANCELLED)
        assertThat(found.cancelledAt).isEqualTo(now.plusSeconds(10))
    }

    @Test
    fun `오래된 버전으로 저장하면 낙관적 락 예외가 난다`() {
        val saved = tx.execute { port.save(newBooking()) }!!
        val firstLoaded = tx.execute { port.findById(saved.id!!) }!!
        val secondLoaded = tx.execute { port.findById(saved.id!!) }!!

        firstLoaded.cancelByUser(firstLoaded.memberId, now)
        tx.execute { port.save(firstLoaded) }
        secondLoaded.cancelByUser(secondLoaded.memberId, now)

        assertThatThrownBy { tx.execute { port.save(secondLoaded) } }.isInstanceOf(ObjectOptimisticLockingFailureException::class.java)
    }

    @Test
    fun `같은 회원과 멱등 키는 조회되고 중복 저장은 유니크 제약으로 거부된다`() {
        val member = ids.incrementAndGet()
        val saved = tx.execute { port.save(newBooking(member = member, key = "key-1")) }!!

        assertThat(tx.execute { port.findByMemberAndIdempotencyKey(MemberId(member), "key-1") }!!.id).isEqualTo(saved.id)
        assertThat(tx.execute { port.findByMemberAndIdempotencyKey(MemberId(member), "other") }).isNull()
        assertThatThrownBy { tx.execute { port.save(newBooking(member = member, key = "key-1")) } }
            .isInstanceOf(DataIntegrityViolationException::class.java)

        // 다른 회원은 같은 키를 쓸 수 있다
        tx.execute { port.save(newBooking(key = "key-1")) }
    }

    @Test
    fun `같은 회차 좌석을 두 번째로 넣으면 삽입 수가 0이다`() {
        val schedule = ScheduleId(ids.incrementAndGet())
        val first = tx.execute { port.save(newBooking(schedule = schedule.value)) }!!.id!!
        val second = tx.execute { port.save(newBooking(schedule = schedule.value)) }!!.id!!

        assertThat(tx.execute { port.insertSoldSeats(first, schedule, listOf(SeatId(1), SeatId(2)), now) }).isEqualTo(2)
        assertThat(tx.execute { port.insertSoldSeats(second, schedule, listOf(SeatId(1)), now) }).isEqualTo(0)
        assertThat(tx.execute { port.insertSoldSeats(second, schedule, listOf(SeatId(1), SeatId(3)), now) }).isEqualTo(1)
    }

    @Test
    fun `판매 좌석을 지우면 같은 좌석을 다시 넣을 수 있다`() {
        val schedule = ScheduleId(ids.incrementAndGet())
        val first: BookingId = tx.execute { port.save(newBooking(schedule = schedule.value)) }!!.id!!
        val second: BookingId = tx.execute { port.save(newBooking(schedule = schedule.value)) }!!.id!!
        tx.execute { port.insertSoldSeats(first, schedule, listOf(SeatId(1)), now) }

        tx.execute { port.deleteSoldSeats(first) }

        assertThat(tx.execute { port.insertSoldSeats(second, schedule, listOf(SeatId(1)), now) }).isEqualTo(1)
    }
}
