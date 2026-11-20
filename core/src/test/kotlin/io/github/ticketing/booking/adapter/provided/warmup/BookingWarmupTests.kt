package io.github.ticketing.booking.adapter.provided.warmup

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.booking.BookingConfirmed
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter
import io.github.ticketing.booking.adapter.required.redis.ScheduleSeatCache
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.domain.HoldToken
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId
import io.github.ticketing.payment.PaymentRequested
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.ReadinessState
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import ticketing.jooq.booking.tables.references.BOOKING_
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

@TestPropertySource(
    properties = [
        "ticketing.startup-warmup.enabled=true",
        "ticketing.startup-warmup.local-iterations=4",
        "ticketing.startup-warmup.adapter-iterations=4",
    ],
)
@RecordApplicationEvents
internal class BookingWarmupTests(
    private val work: BookingWarmupWork,
    private val dsl: DSLContext,
    private val redis: StringRedisTemplate,
    private val cache: ScheduleSeatCache,
    private val holds: SeatHoldPort,
    private val availability: ApplicationAvailability,
) : IntegrationTestSupport() {
    @Test
    fun `실제 승자와 패자를 예열해도 DB와 Redis 및 이벤트가 남지 않는다`(events: ApplicationEvents) {
        val counts = WarmupIoCounts()

        val outcomes = WarmupIo.measure(counts) { listOf(work.exercise(false), work.exercise(true)) }

        assertThat(outcomes).containsExactly(true, false)
        assertThat(counts.dbStatements.get()).isGreaterThanOrEqualTo(3)
        assertThat(counts.redisCommands.get()).isEqualTo(9)
        assertThat(dsl.fetchCount(BOOKING_, BOOKING_.SCHEDULE_ID.lt(0))).isZero()
        assertThat(redis.keys("booking:warmup:*")).isEmpty()
        assertThat(events.stream(BookingConfirmed::class.java).count()).isZero()
        assertThat(events.stream(PaymentRequested::class.java).count()).isZero()
        assertThat(availability.readinessState).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC)
    }

    @Test
    fun `예열 키에는 생성 즉시 TTL이 있고 일반 SSE 키는 만들지 않는다`() {
        val schedule = ScheduleId(-System.nanoTime())
        val seat = SeatId(1)
        val keys =
            listOf(
                ScheduleSeatCache.key(schedule),
                RedisSeatHoldAdapter.holdKey(schedule, seat),
                RedisSeatHoldAdapter.snapshotKey(schedule),
                RedisSeatHoldAdapter.streamKey(schedule),
            )
        try {
            cache.putForWarmup(schedule, listOf(ScheduleSeatCopy(schedule, seat, "VIP", "W", BigDecimal.TEN)))
            holds.hold(
                schedule,
                listOf(seat),
                HoldToken("warmup"),
                Duration.ofMinutes(5),
                Instant.parse("2026-11-16T00:00:00Z"),
                "a".repeat(64),
            )

            keys.forEach { assertThat(redis.getExpire(it)).isBetween(1L, 30L) }
            assertThat(redis.keys("booking:warmup:hold-request:{${schedule.value}}:*")).isEmpty()
            assertThat(redis.hasKey("booking:seat-events:{${schedule.value}}")).isFalse()
            assertThat(redis.hasKey("booking:seats:{${schedule.value}}")).isFalse()
        } finally {
            redis.delete(keys)
        }
    }

    @Test
    fun `시간 상한에 도달하면 예열을 취소하고 반환한다`() {
        val properties = BookingWarmupProperties(maxDuration = Duration.ofNanos(1))
        val started = System.nanoTime()

        BookingWarmupRunner(work, properties).run(DefaultApplicationArguments())

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1))
    }
}
