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
import io.github.ticketing.booking.domain.SeatId
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
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
) : CreateBookingUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun create(command: CreateBookingCommand): CreateBookingResult {
        val memberId = MemberId(command.memberId)
        existing(memberId, command.idempotencyKey)?.let { return it }

        val scheduleId = ScheduleId(command.scheduleId)
        val requested = command.seatIds.map { SeatId(it) }
        val copies = scheduleSeats.findSeats(scheduleId, requested).associateBy { it.seatId }
        if (copies.isEmpty() && !scheduleSeats.existsSchedule(scheduleId)) throw ScheduleNotFoundException()

        val seats = requested.map { id ->
            val copy = copies[id] ?: throw SeatNotFoundException()
            BookedSeat(id, copy.price)
        }

        val now = clock.instant()
        val holdToken = HoldToken(UUID.randomUUID().toString())
        // 좌석 수·중복 검증이 Redis 선점보다 먼저 일어나도록 도메인 객체부터 만든다
        val booking = translatingRuleViolations {
            Booking.create(memberId, scheduleId, seats, holdToken, now, command.idempotencyKey)
        }

        when (val held = seatHold.hold(scheduleId, requested, holdToken, Booking.HOLD_DURATION, now)) {
            is HoldResult.Unavailable -> {
                // 같은 요청이 동시에 두 번 들어오면 한쪽은 선점에 실패한다. 먼저 끝난 쪽의 예매를 돌려준다
                existing(memberId, command.idempotencyKey)?.let { return it }
                throw SeatAlreadyHeldException(held.seatIds.map { it.value })
            }
            HoldResult.Held -> Unit
        }

        val saved = try {
            requireNotNull(tx.execute { bookings.save(booking) })
        } catch (e: Exception) {
            runCatching { seatHold.release(scheduleId, requested, holdToken, clock.instant()) }
                .onFailure { log.warn("예매 저장 실패 후 선점 해제에 실패했다. TTL로 만료된다. scheduleId={}", scheduleId.value, it) }
            if (e is DataIntegrityViolationException) existing(memberId, command.idempotencyKey)?.let { return it }
            throw e
        }
        log.info("예매 생성 bookingId={} memberId={}", saved.id?.value, memberId.value)
        return CreateBookingResult(saved.toResult(copies.mapValues { it.value.label }), created = true)
    }

    private fun existing(memberId: MemberId, key: String): CreateBookingResult? {
        val booking = bookings.findByMemberAndIdempotencyKey(memberId, key) ?: return null
        val labels = scheduleSeats.findSeats(booking.scheduleId, booking.seats.map { it.seatId }).associate { it.seatId to it.label }
        return CreateBookingResult(booking.toResult(labels), created = false)
    }
}
