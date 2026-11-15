package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.application.port.provided.CreateBookingCommand
import io.github.ticketing.booking.application.port.provided.CreateBookingUseCase
import io.github.ticketing.booking.application.port.required.QueryRenderWarmupPort
import io.github.ticketing.booking.application.port.required.WarmupFixturePort
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

data class WarmupPlan(
    val rounds: Int,
    val requestsPerRound: Int,
    val losersPerWinner: Int,
    val concurrency: Int,
    val maxRequestsPerSecond: Int,
    val timeLimit: Duration,
) {
    init {
        require(rounds > 0 && requestsPerRound > 0 && losersPerWinner >= 0) { "예열 계획 값이 올바르지 않다: $this" }
        require(concurrency > 0 && maxRequestsPerSecond > 0) { "동시성과 초당 상한은 1 이상이어야 한다: $this" }
    }
}

data class WarmupReport(
    val requests: Int,
    val winners: Int,
    val losers: Int,
    val errors: Int,
    val redisCommands: Int,
    val dbStatements: Int,
    val truncatedByTimeLimit: Boolean,
    val elapsed: Duration,
)

/**
 * 실제 [CreateBookingUseCase]를 실제 어댑터로 호출해 JIT 프로파일을 실제 요청과 같게 채운다(stub 포트는 타입 프로파일을 오염시킨다).
 * DB는 바깥 트랜잭션을 롤백 전용으로 두어 남기지 않고, Redis는 실제 회차와 겹치지 않는 예열 회차 키를 쓰고 회차마다 지운다.
 * 예매 생성은 이벤트를 발행하지 않으므로 롤백한 예열 예매가 다른 모듈로 흘러가지 않는다.
 */
@Service
class BookingWarmupService(
    private val createBooking: CreateBookingUseCase,
    transactionManager: PlatformTransactionManager,
    private val fixtures: WarmupFixturePort,
    private val queryRender: QueryRenderWarmupPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rollbackOnly = TransactionTemplate(transactionManager)

    fun warmQueryRendering(iterations: Int) = queryRender.renderBookingStatements(iterations)

    fun warmCreateBooking(plan: WarmupPlan): WarmupReport {
        val started = System.nanoTime()
        val deadline = started + plan.timeLimit.toNanos()
        val counters = Counters()
        val limiter = RateLimiter(plan.maxRequestsPerSecond)
        var truncated = false
        for (round in 0 until plan.rounds) {
            if (System.nanoTime() >= deadline) {
                truncated = true
                break
            }
            runRound(round, plan, deadline, limiter, counters)
        }
        return counters.toReport(truncated, Duration.ofNanos(System.nanoTime() - started))
    }

    private fun runRound(round: Int, plan: WarmupPlan, deadline: Long, limiter: RateLimiter, counters: Counters) {
        val scheduleId = ScheduleId(WARMUP_SCHEDULE_BASE + round)
        val groupSize = plan.losersPerWinner + 1
        val groupCount = (plan.requestsPerRound + groupSize - 1) / groupSize
        val seatIds = (1L..groupCount * SEATS_PER_REQUEST).map(::SeatId)
        fixtures.seedSeatCopies(scheduleId, seatIds.map { ScheduleSeatCopy(scheduleId, it, "R", "warmup-${it.value}", SEAT_PRICE) })
        counters.redisCommands.incrementAndGet()
        try {
            val permits = Semaphore(plan.concurrency)
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory()).use { executor ->
                for (index in 0 until plan.requestsPerRound) {
                    if (System.nanoTime() >= deadline) break
                    permits.acquire()
                    limiter.await()
                    executor.execute {
                        try {
                            request(round, index, scheduleId, index / groupSize, counters)
                        } finally {
                            permits.release()
                        }
                    }
                }
            }
        } finally {
            fixtures.cleanup(scheduleId, seatIds)
            counters.redisCommands.incrementAndGet()
        }
    }

    // 같은 묶음의 첫 요청이 선점에 성공하고 나머지는 409로 진다. 승자 수가 실제 폭주의 비율(약 6%)에 가깝게 한다
    @Suppress("TooGenericExceptionCaught")
    private fun request(round: Int, index: Int, scheduleId: ScheduleId, group: Int, counters: Counters) {
        val firstSeat = group * SEATS_PER_REQUEST + 1L
        val command = CreateBookingCommand(
            memberId = -(index + 1L),
            idempotencyKey = "warmup-$round-$index",
            scheduleId = scheduleId.value,
            seatIds = (firstSeat until firstSeat + SEATS_PER_REQUEST).toList(),
        )
        rollbackOnly.execute { status ->
            try {
                if (createBooking.create(command).created) counters.countWinner() else counters.countError()
            } catch (conflict: SeatAlreadyHeldException) {
                log.trace("예열 패자 요청(정상 409). round={}, index={}", round, index, conflict)
                counters.countLoser()
            } catch (e: RuntimeException) {
                if (counters.countError() <= MAX_LOGGED_ERRORS) log.warn("예열 예매 요청이 실패했다. round={}, index={}", round, index, e)
            }
            status.setRollbackOnly()
        }
    }

    private class Counters {
        val winners = AtomicInteger()
        val losers = AtomicInteger()
        val errors = AtomicInteger()
        val redisCommands = AtomicInteger()

        // 승자: 사본 HMGET, 선점 Lua. 패자: 사본 HMGET, 선점 Lua
        fun countWinner() {
            winners.incrementAndGet()
            redisCommands.addAndGet(2)
        }

        fun countLoser() {
            losers.incrementAndGet()
            redisCommands.addAndGet(2)
        }

        fun countError() = errors.incrementAndGet()

        // 승자는 booking·booking_seat INSERT 2문, 패자는 멱등 조회 1문
        fun toReport(truncated: Boolean, elapsed: Duration) = WarmupReport(
            requests = winners.get() + losers.get() + errors.get(),
            winners = winners.get(),
            losers = losers.get(),
            errors = errors.get(),
            redisCommands = redisCommands.get(),
            dbStatements = winners.get() * 2 + losers.get(),
            truncatedByTimeLimit = truncated,
            elapsed = elapsed,
        )
    }

    // 요청 시작 시각을 초당 상한 간격으로 벌려, 예열이 공유 자원에 순간 부하를 주지 않게 한다
    private class RateLimiter(maxPerSecond: Int) {
        private val intervalNanos = 1_000_000_000L / maxPerSecond
        private val nextSlot = AtomicLong(System.nanoTime())

        fun await() {
            val slot = nextSlot.getAndAdd(intervalNanos)
            val wait = slot - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait)
        }
    }

    private companion object {
        // 실제 회차 ID와 겹치지 않는 영역. 예열 회차는 이 값에 회차 번호를 더한다
        const val WARMUP_SCHEDULE_BASE = 9_000_000_000_000_000_000L
        const val SEATS_PER_REQUEST = 2L
        const val MAX_LOGGED_ERRORS = 3
        val SEAT_PRICE: BigDecimal = BigDecimal.valueOf(50_000)
    }
}
