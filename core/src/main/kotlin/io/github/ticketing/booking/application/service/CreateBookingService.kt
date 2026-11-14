package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.application.port.provided.CreateBookingCommand
import io.github.ticketing.booking.application.port.provided.CreateBookingResult
import io.github.ticketing.booking.application.port.provided.CreateBookingUseCase
import io.github.ticketing.booking.application.port.required.BookingCommandPort
import io.github.ticketing.booking.application.port.required.HoldResult
import io.github.ticketing.booking.application.port.required.ScheduleSeatCommandPort
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.domain.BookedSeat
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.HoldToken
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Redis 선점은 DB 트랜잭션 밖에서 하고, 저장만 짧은 트랜잭션으로 감싼다.
 * 저장이 실패하면 선점을 되돌린다(Redis TTL이 최후의 보정 수단이다).
 */
@Service
class CreateBookingService(
    private val bookings: BookingCommandPort,
    private val scheduleSeats: ScheduleSeatCommandPort,
    private val seatHold: SeatHoldPort,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    private val registry: MeterRegistry,
) : CreateBookingUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun <T> stage(name: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            io.micrometer.core.instrument.Timer.builder("ticketing.booking.create.stage").tag("stage", name).publishPercentileHistogram()
                .register(registry).record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS)
        }
    }

    override fun create(command: CreateBookingCommand): CreateBookingResult {
        val memberId = MemberId(command.memberId)
        return stage("existing") { existing(memberId, command.idempotencyKey) } ?: createNew(memberId, command)
    }

    private fun createNew(memberId: MemberId, command: CreateBookingCommand): CreateBookingResult {
        val scheduleId = ScheduleId(command.scheduleId)
        val requested = command.seatIds.map { SeatId(it) }
        val copies = stage("find-copies") { findCopies(scheduleId, requested) }
        val seats = requested.map { id -> BookedSeat(id, copies.getValue(id).price) }

        val now = clock.instant()
        val holdToken = HoldToken(UUID.randomUUID().toString())
        // 좌석 수·중복 검증이 Redis 선점보다 먼저 일어나도록 도메인 객체부터 만든다
        val booking = translatingRuleViolations {
            Booking.create(memberId, scheduleId, seats, holdToken, now, command.idempotencyKey)
        }

        stage("hold") { holdOrExisting(memberId, command.idempotencyKey, scheduleId, requested, holdToken, now) }?.let { return it }
        return stage("persist") { persist(booking, copies.mapValues { it.value.label }, requested) }
    }

    private fun findCopies(scheduleId: ScheduleId, requested: List<SeatId>): Map<SeatId, ScheduleSeatCopy> {
        val copies = scheduleSeats.findSeats(scheduleId, requested).associateBy { it.seatId }
        if (copies.isEmpty() && !scheduleSeats.existsSchedule(scheduleId)) throw ScheduleNotFoundException()
        if (!copies.keys.containsAll(requested)) throw SeatNotFoundException()
        return copies
    }

    // 같은 요청이 동시에 두 번 들어오면 한쪽은 선점에 실패한다. 먼저 끝난 쪽의 예매를 돌려준다
    private fun holdOrExisting(
        memberId: MemberId,
        key: String,
        scheduleId: ScheduleId,
        requested: List<SeatId>,
        holdToken: HoldToken,
        now: Instant,
    ): CreateBookingResult? = when (val held = seatHold.hold(scheduleId, requested, holdToken, Booking.HOLD_DURATION, now)) {
        is HoldResult.Unavailable -> existing(memberId, key) ?: throw SeatAlreadyHeldException(held.seatIds.map { it.value })
        HoldResult.Held -> null
    }

    private fun persist(booking: Booking, labels: Map<SeatId, String>, requested: List<SeatId>): CreateBookingResult {
        val saved = try {
            save(booking, requested)
        } catch (e: DataIntegrityViolationException) {
            return existing(booking.memberId, booking.idempotencyKey) ?: throw e
        }
        log.info("예매 생성 bookingId={} memberId={}", saved.id?.value, booking.memberId.value)
        return CreateBookingResult(saved.toResult(labels), created = true)
    }

    // 저장이 실패하면 선점을 되돌리고 원래 예외를 그대로 던진다
    @Suppress("TooGenericExceptionCaught")
    private fun save(booking: Booking, requested: List<SeatId>): Booking = try {
        requireNotNull(tx.execute { bookings.save(booking) })
    } catch (e: Exception) {
        runCatching { seatHold.release(booking.scheduleId, requested, booking.holdToken, clock.instant()) }
            .onFailure { log.warn("예매 저장 실패 후 선점 해제에 실패했다. TTL로 만료된다. scheduleId={}", booking.scheduleId.value, it) }
        throw e
    }

    private fun existing(memberId: MemberId, key: String): CreateBookingResult? {
        val booking = bookings.findByMemberAndIdempotencyKey(memberId, key) ?: return null
        val labels = scheduleSeats.findSeats(booking.scheduleId, booking.seats.map { it.seatId }).associate { it.seatId to it.label }
        return CreateBookingResult(booking.toResult(labels), created = false)
    }
}
