package io.github.ticketing.booking.adapter.provided.warmup

import io.github.ticketing.booking.adapter.provided.web.BookingResponse
import io.github.ticketing.booking.adapter.provided.web.BookingSeatResponse
import io.github.ticketing.booking.adapter.provided.web.CreateBookingRequest
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter
import io.github.ticketing.booking.adapter.required.redis.ScheduleSeatCache
import io.github.ticketing.booking.application.port.provided.BookingDetailView
import io.github.ticketing.booking.application.port.provided.BookingQueryUseCase
import io.github.ticketing.booking.application.port.provided.CreateBookingCommand
import io.github.ticketing.booking.application.port.provided.CreateBookingResult
import io.github.ticketing.booking.application.port.provided.CreateBookingUseCase
import io.github.ticketing.booking.application.port.provided.PaymentRequestResult
import io.github.ticketing.booking.application.port.provided.RequestPaymentUseCase
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
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import ticketing.jooq.booking.tables.references.BOOKING_
import ticketing.jooq.booking.tables.references.SCHEDULE
import ticketing.jooq.booking.tables.references.SCHEDULE_SEAT
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.util.UUID

private const val WARMUP_TTL_SECONDS = 30L
private const val WARMUP_START_OFFSET_SECONDS = 86400L
private const val MAX_SEATS = 4

@Component
internal class BookingWarmupWork(
    @Qualifier("createBookingService") private val createBooking: CreateBookingUseCase,
    @Qualifier("bookingQueryService") private val queries: BookingQueryUseCase,
    @Qualifier("requestPaymentService") private val payments: RequestPaymentUseCase,
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
        check(booking.listAmount == BigDecimal.TEN)
        mapper.writeValueAsString(
            BookingResponse(
                1,
                booking.status.name,
                booking.listAmount,
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

    /** 직접 호출 예열(러너)용이다. 승자는 응답 직렬화까지 거치고 패자는 선점 충돌 예외를 삼킨다. */
    fun exercise(conflict: Boolean, seatCount: Int = 1): Boolean = try {
        val result = create(conflict, seatCount)
        mapper.writeValueAsString(BookingResponse.from(result.booking))
        true
    } catch (_: SeatAlreadyHeldException) {
        false
    }

    /**
     * 음수 회차 격리 환경에서 실제 예매 생성 유스케이스를 호출한다. DB에 쓴 데이터는 롤백하고, 패자는
     * [SeatAlreadyHeldException]을 그대로 던진다. 컨트롤러 아래(HTTP 경유 예열)에서도 같은 격리를 쓴다.
     * 실제 요청의 좌석 수(1~4)를 따라 [seatCount]장을 잡는다.
     */
    fun create(conflict: Boolean, seatCount: Int = 1): CreateBookingResult = isolated(seatCount) { schedule, seats ->
        rolledBack {
            insertSchedule(schedule, seats)
            if (conflict) {
                holds.hold(schedule, seats, HoldToken("startup-holder"), Duration.ofSeconds(WARMUP_TTL_SECONDS), clock.instant())
            }
            createBooking.create(command(schedule, seats)).also {
                check(!conflict) { "예열 경합 요청이 성공했습니다." }
                check(it.created)
            }
        }
    }

    /** 예매 상세 조회 예열이다. 롤백 트랜잭션 안에서 만든 예매를 실제 조회 유스케이스로 읽는다. */
    fun detail(): BookingDetailView = isolated(1) { schedule, seats ->
        rolledBack {
            insertSchedule(schedule, seats)
            val created = createBooking.create(command(schedule, seats)).also { check(it.created) }
            queries.detail(schedule.value, created.booking.bookingId)
        }
    }

    /**
     * 결제 요청 예열이다. 롤백 트랜잭션 안에서 만든 예매에 실제 결제 요청 유스케이스를 호출한다. 발행되는 [PaymentRequested]는
     * 트랜잭션과 함께 사라져 커밋 뒤 외부화(Kafka 발행)가 일어나지 않는다.
     */
    fun requestPayment(): PaymentRequestResult = isolated(1) { schedule, seats ->
        rolledBack {
            insertSchedule(schedule, seats)
            val created = createBooking.create(command(schedule, seats)).also { check(it.created) }
            payments.request(schedule.value, created.booking.bookingId)
        }
    }

    private fun command(schedule: ScheduleId, seats: List<SeatId>) =
        CreateBookingCommand(schedule.value, UUID.randomUUID().toString(), schedule.value, seats.map { it.value })

    // 회차가 음수라 실제 데이터와 겹치지 않는다. 롤백 트랜잭션 안에서만 보이는 행이다.
    private fun insertSchedule(schedule: ScheduleId, seats: List<SeatId>) {
        val now = clock.instant().atOffset(java.time.ZoneOffset.UTC)
        dsl.insertInto(SCHEDULE)
            .set(SCHEDULE.SCHEDULE_ID, schedule.value)
            .set(SCHEDULE.PERFORMANCE_ID, schedule.value)
            .set(SCHEDULE.PERFORMANCE_TITLE, "warmup")
            .set(SCHEDULE.ORGANIZER_ID, schedule.value)
            .set(SCHEDULE.STARTS_AT, now.plusDays(1))
            .set(SCHEDULE.RECEIVED_AT, now)
            .execute()
        seats.forEach { seat ->
            dsl.insertInto(SCHEDULE_SEAT)
                .set(SCHEDULE_SEAT.SCHEDULE_ID, schedule.value)
                .set(SCHEDULE_SEAT.SEAT_ID, seat.value)
                .set(SCHEDULE_SEAT.GRADE, "VIP")
                .set(SCHEDULE_SEAT.LABEL, "W-${seat.value}")
                .set(SCHEDULE_SEAT.PRICE, BigDecimal.TEN.toLong())
                .execute()
        }
    }

    private fun <T> rolledBack(action: () -> T): T = checkNotNull(
        rollback.execute { status ->
            try {
                action()
            } finally {
                status.setRollbackOnly()
            }
        },
    )

    private fun <T> isolated(seatCount: Int, action: (ScheduleId, List<SeatId>) -> T): T {
        require(seatCount in 1..MAX_SEATS)
        val schedule = ScheduleId(-((UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1)))
        val seats = (1..seatCount).map { SeatId(it.toLong()) }
        val keys =
            listOf(ScheduleSeatCache.key(schedule), RedisSeatHoldAdapter.snapshotKey(schedule), RedisSeatHoldAdapter.streamKey(schedule)) +
                seats.map { RedisSeatHoldAdapter.holdKey(schedule, it) }
        return try {
            ensureActive()
            cache.putForWarmup(
                schedule,
                seats.map {
                    ScheduleSeatCopy(
                        schedule,
                        it,
                        "VIP",
                        "W-${it.value}",
                        BigDecimal.TEN,
                        clock.instant().plusSeconds(WARMUP_START_OFFSET_SECONDS),
                    )
                },
            )
            ensureActive()
            action(schedule, seats)
        } finally {
            WarmupIo.redisCommand()
            redis.delete(keys)
        }
    }
}
