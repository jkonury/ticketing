package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.application.port.provided.BookingResult
import io.github.ticketing.booking.application.port.provided.CreateBookingCommand
import io.github.ticketing.booking.application.port.provided.CreateBookingResult
import io.github.ticketing.booking.application.port.provided.CreateBookingUseCase
import io.github.ticketing.booking.application.port.required.QueryRenderWarmupPort
import io.github.ticketing.booking.application.port.required.WarmupFixturePort
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class BookingWarmupServiceTests {
    private class RecordingTransactionManager : AbstractPlatformTransactionManager() {
        val commits = AtomicInteger()
        val rollbacks = AtomicInteger()
        override fun doGetTransaction(): Any = Any()
        override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
        override fun doCommit(status: DefaultTransactionStatus) {
            commits.incrementAndGet()
        }
        override fun doRollback(status: DefaultTransactionStatus) {
            rollbacks.incrementAndGet()
        }
    }

    // 좌석 묶음의 첫 요청만 선점에 성공하고 나머지는 409로 진다
    private class FirstWinsCreateBooking : CreateBookingUseCase {
        val commands = CopyOnWriteArrayList<CreateBookingCommand>()
        private val held = ConcurrentHashMap.newKeySet<Pair<Long, List<Long>>>()

        override fun create(command: CreateBookingCommand): CreateBookingResult {
            commands += command
            if (!held.add(command.scheduleId to command.seatIds)) throw SeatAlreadyHeldException(command.seatIds)
            return CreateBookingResult(BookingResult(1, "PENDING", BigDecimal.TEN, Instant.EPOCH, emptyList()), created = true)
        }
    }

    private class RecordingFixtures : WarmupFixturePort {
        val seeded = CopyOnWriteArrayList<ScheduleId>()
        val cleaned = CopyOnWriteArrayList<Pair<ScheduleId, List<SeatId>>>()
        override fun seedSeatCopies(scheduleId: ScheduleId, seats: List<ScheduleSeatCopy>) {
            seeded += scheduleId
        }
        override fun cleanup(scheduleId: ScheduleId, seatIds: List<SeatId>) {
            cleaned += scheduleId to seatIds
        }
    }

    private val transactions = RecordingTransactionManager()
    private val useCase = FirstWinsCreateBooking()
    private val fixtures = RecordingFixtures()
    private val noRender = object : QueryRenderWarmupPort {
        override fun renderBookingStatements(iterations: Int) = Unit
    }
    private val service = BookingWarmupService(useCase, transactions, fixtures, noRender)

    private fun plan(rounds: Int = 2, timeLimit: Duration = Duration.ofSeconds(10)) = WarmupPlan(
        rounds = rounds,
        requestsPerRound = 8,
        losersPerWinner = 3,
        concurrency = 2,
        maxRequestsPerSecond = 10_000,
        timeLimit = timeLimit,
    )

    @Test
    fun `묶음마다 승자 한 건과 패자를 만들고 DB 트랜잭션은 모두 롤백한다`() {
        val report = service.warmCreateBooking(plan())

        assertThat(report.requests).isEqualTo(16)
        assertThat(report.winners).isEqualTo(4)
        assertThat(report.losers).isEqualTo(12)
        assertThat(report.errors).isZero()
        assertThat(transactions.rollbacks.get()).isEqualTo(16)
        assertThat(transactions.commits.get()).isZero()
    }

    @Test
    fun `예열 회차는 실제 회차와 겹치지 않는 ID를 쓰고 회차마다 사본을 채우고 지운다`() {
        service.warmCreateBooking(plan())

        assertThat(useCase.commands.map { it.scheduleId }.toSet()).allMatch { it > 1_000_000_000_000L }
        assertThat(useCase.commands.map { it.memberId }).allMatch { it < 0 }
        assertThat(fixtures.seeded).hasSize(2)
        assertThat(fixtures.cleaned.map { it.first }).containsExactlyInAnyOrderElementsOf(fixtures.seeded)
        assertThat(fixtures.cleaned).allMatch { (_, seatIds) -> seatIds.size == 4 }
    }

    @Test
    fun `예매 요청이 예상 밖 예외로 실패하면 오류로 세고 회차 정리는 그대로 한다`() {
        val failing = object : CreateBookingUseCase {
            override fun create(command: CreateBookingCommand): CreateBookingResult = throw IllegalStateException("boom")
        }
        val failingService = BookingWarmupService(failing, transactions, fixtures, noRender)

        val report = failingService.warmCreateBooking(plan(rounds = 1))

        assertThat(report.errors).isEqualTo(8)
        assertThat(report.winners).isZero()
        assertThat(fixtures.cleaned).hasSize(1)
    }

    @Test
    fun `시간 상한이 이미 지났으면 요청을 보내지 않고 중단 표시를 남긴다`() {
        val report = service.warmCreateBooking(plan(timeLimit = Duration.ZERO))

        assertThat(report.requests).isZero()
        assertThat(report.truncatedByTimeLimit).isTrue()
        assertThat(fixtures.seeded).isEmpty()
    }

    @Test
    fun `잘못된 예열 계획은 만들 수 없다`() {
        assertThatThrownBy { plan(rounds = 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
