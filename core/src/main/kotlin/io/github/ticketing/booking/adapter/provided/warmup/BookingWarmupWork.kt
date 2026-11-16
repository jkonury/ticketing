package io.github.ticketing.booking.adapter.provided.warmup

import io.github.ticketing.booking.adapter.provided.web.BookingResponse
import io.github.ticketing.booking.adapter.provided.web.BookingSeatResponse
import io.github.ticketing.booking.adapter.provided.web.CreateBookingRequest
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter
import io.github.ticketing.booking.adapter.required.redis.ScheduleSeatCache
import io.github.ticketing.booking.application.port.provided.CreateBookingCommand
import io.github.ticketing.booking.application.port.provided.CreateBookingUseCase
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.application.service.SeatAlreadyHeldException
import io.github.ticketing.booking.domain.BookedSeat
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.HoldToken
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.protocol.CommandArgs
import io.netty.buffer.Unpooled
import jakarta.validation.Validator
import org.jooq.DSLContext
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import ticketing.jooq.booking.tables.references.BOOKING_
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.util.UUID

private const val WARMUP_TTL_SECONDS = 30L

@Component
internal class BookingWarmupWork(
    private val createBooking: CreateBookingUseCase,
    private val cache: ScheduleSeatCache,
    private val holds: SeatHoldPort,
    private val redis: StringRedisTemplate,
    private val mapper: JsonMapper,
    private val validator: Validator,
    private val dsl: DSLContext,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) {
    private val rollback = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        timeout = 2
    }

    fun localIteration() {
        val request = mapper.readValue("""{"scheduleId":1,"seatIds":[1]}""", CreateBookingRequest::class.java)
        check(validator.validate(request).isEmpty())
        val booking = Booking.create(
            MemberId(1),
            ScheduleId(1),
            listOf(BookedSeat(SeatId(1), BigDecimal.TEN)),
            HoldToken("warmup"),
            clock.instant(),
            "warmup",
        )
        check(booking.totalPrice == BigDecimal.TEN)
        mapper.writeValueAsString(
            BookingResponse(
                1,
                booking.status.name,
                booking.totalPrice,
                booking.expiresAt,
                listOf(BookingSeatResponse(1, "W-1", BigDecimal.TEN)),
            ),
        )
        dsl.select(BOOKING_.ID).from(BOOKING_).where(BOOKING_.MEMBER_ID.eq(1L)).sql
        val buffer = Unpooled.buffer()
        try {
            CommandArgs(StringCodec.UTF8).addKey("booking:warmup:encoding").addValue("warmup").encode(buffer)
        } finally {
            buffer.release()
        }
    }

    private fun ensureActive() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("예열 취소")
    }

    fun exercise(conflict: Boolean): Boolean {
        val schedule = ScheduleId(-((UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1)))
        val seat = SeatId(1)
        val keys =
            listOf(
                ScheduleSeatCache.key(schedule),
                RedisSeatHoldAdapter.holdKey(schedule, seat),
                RedisSeatHoldAdapter.snapshotKey(schedule),
                RedisSeatHoldAdapter.streamKey(schedule),
            )
        return try {
            ensureActive()
            cache.putForWarmup(schedule, listOf(ScheduleSeatCopy(schedule, seat, "VIP", "W-1", BigDecimal.TEN)))
            ensureActive()
            if (conflict) {
                holds.hold(
                    schedule,
                    listOf(seat),
                    HoldToken("startup-holder"),
                    Duration.ofSeconds(WARMUP_TTL_SECONDS),
                    clock.instant(),
                )
            }
            val command = CreateBookingCommand(schedule.value, UUID.randomUUID().toString(), schedule.value, listOf(seat.value))
            ensureActive()
            if (conflict) {
                try {
                    createBooking.create(command)
                    error("예열 경합 요청이 성공했습니다.")
                } catch (_: SeatAlreadyHeldException) {
                    false
                }
            } else {
                rollback.execute { status ->
                    try {
                        val result = createBooking.create(command)
                        mapper.writeValueAsString(BookingResponse.from(result.booking))
                        check(result.created)
                    } finally {
                        status.setRollbackOnly()
                    }
                }
                true
            }
        } finally {
            WarmupIo.redisCommand()
            redis.delete(keys)
        }
    }
}
