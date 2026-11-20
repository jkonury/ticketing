package io.github.ticketing.booking.adapter.provided.batch

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.MutableClock
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter.Companion.snapshotKey
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter.Companion.streamKey
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import io.github.ticketing.booking.application.port.required.BookingQueryPort
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.application.service.ReconcileSeatSnapshotService
import io.github.ticketing.booking.application.service.SeatSnapshotQueries
import io.github.ticketing.booking.domain.HoldToken
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.event.RecordApplicationEvents
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

@RecordApplicationEvents
class ReconcileSeatSnapshotJobTests(
    private val operator: JobOperator,
    private val reconcileSeatSnapshotJob: Job,
    private val register: RegisterScheduleSeatsUseCase,
    private val redis: StringRedisTemplate,
    private val clock: MutableClock,
    private val holds: SeatHoldPort,
) : IntegrationTestSupport() {
    @BeforeEach
    fun fixClock() {
        clock.now = NOW
    }

    @Test
    fun `시작 전 회차만 보정하고 재실행은 변경하지 않는다`() {
        val upcoming = ScheduleId(880001)
        val started = ScheduleId(880002)
        register(upcoming, NOW.plusSeconds(3600))
        register(started, NOW.minusSeconds(1))
        val hash = redis.opsForHash<String, String>()
        hash.put(snapshotKey(upcoming), "1", "SOLD")
        hash.put(snapshotKey(started), "1", "SOLD")

        val first = operator.start(reconcileSeatSnapshotJob, parameters(1))
        assertThat(first.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(hash.get(snapshotKey(upcoming), "1")).isEqualTo("AVAILABLE")
        assertThat(hash.get(snapshotKey(started), "1")).isEqualTo("SOLD")
        val before = redis.opsForStream<String, String>().size(streamKey(upcoming))
        val second = operator.start(reconcileSeatSnapshotJob, parameters(2))

        assertThat(second.status).isEqualTo(BatchStatus.COMPLETED)
        // changed 합계는 같은 컨텍스트의 다른 테스트 회차를 포함하므로 이 테스트의 회차 상태로만 단언한다.
        assertThat(hash.get(snapshotKey(upcoming), "1")).isEqualTo("AVAILABLE")
        assertThat(redis.opsForStream<String, String>().size(streamKey(upcoming))).isEqualTo(before)
    }

    @Test
    fun `첫 판매 조회 후 취소된 좌석은 살아 있는 선점으로 복원한다`() {
        val schedule = ScheduleId(880003)
        val seat = SeatId(1)
        val queries = mock(BookingQueryPort::class.java)
        `when`(queries.findSeatIds(schedule.value)).thenReturn(listOf(seat.value))
        `when`(queries.findSoldSeatIds(schedule.value)).thenReturn(setOf(seat.value), emptySet())
        holds.hold(schedule, listOf(seat), HoldToken("owner"), Duration.ofMinutes(5), NOW)

        ReconcileSeatSnapshotService(SeatSnapshotQueries(queries), holds, clock).reconcile(schedule.value)

        assertThat(redis.opsForHash<String, String>().get(snapshotKey(schedule), "1")).startsWith("HELD:")
    }

    private fun register(schedule: ScheduleId, startsAt: Instant) {
        register.register(
            RegisterScheduleSeatsCommand(
                schedule.value,
                1,
                "공연",
                1,
                startsAt,
                listOf(RegisterScheduleSeatsCommand.SeatInfo(1, "VIP", "A1", BigDecimal.TEN)),
            ),
        )
    }

    private fun parameters(run: Long) = JobParametersBuilder().addLong("run.id", run).toJobParameters()

    companion object {
        private val NOW = Instant.parse("2026-11-11T12:00:00Z")
    }
}
