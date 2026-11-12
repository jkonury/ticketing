package io.github.ticketing.booking.adapter.provided.batch

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.MutableClock
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter.Companion.snapshotKey
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter.Companion.streamKey
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import io.github.ticketing.booking.domain.ScheduleId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.event.RecordApplicationEvents
import java.math.BigDecimal
import java.time.Instant

@RecordApplicationEvents
class ReconcileSeatSnapshotJobTests(
    private val launcher: JobLauncher,
    private val reconcileSeatSnapshotJob: Job,
    private val register: RegisterScheduleSeatsUseCase,
    private val redis: StringRedisTemplate,
    private val clock: MutableClock,
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

        val first = launcher.run(reconcileSeatSnapshotJob, parameters(1))
        assertThat(first.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(hash.get(snapshotKey(upcoming), "1")).isEqualTo("AVAILABLE")
        assertThat(hash.get(snapshotKey(started), "1")).isEqualTo("SOLD")
        val before = redis.opsForStream<String, String>().size(streamKey(upcoming))
        val second = launcher.run(reconcileSeatSnapshotJob, parameters(2))

        assertThat(second.status).isEqualTo(BatchStatus.COMPLETED)
        // changed 합계는 같은 컨텍스트의 다른 테스트 회차를 포함하므로 이 테스트의 회차 상태로만 단언한다.
        assertThat(hash.get(snapshotKey(upcoming), "1")).isEqualTo("AVAILABLE")
        assertThat(redis.opsForStream<String, String>().size(streamKey(upcoming))).isEqualTo(before)
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
