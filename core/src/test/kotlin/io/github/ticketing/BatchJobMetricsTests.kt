package io.github.ticketing

import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

@ActiveProfiles(inheritProfiles = false)
@TestPropertySource(properties = ["ticketing.batch.metrics.refresh-interval=100ms"])
@AutoConfigureMetrics
// 이 클래스 전용 스케줄러를 테스트가 끝날 때 닫는다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BatchJobMetricsTests(private val dataSource: DataSource, private val registry: MeterRegistry, private val clock: MutableClock) :
    IntegrationTestSupport() {

    @BeforeEach
    fun setJobTime() {
        clock.now = BASE.plusSeconds(testSequence.incrementAndGet() * 60)
    }

    @Test
    fun `완료된 배치는 실패 값 0과 마지막 성공 시각을 노출한다`() {
        insert("COMPLETED")

        await().atMost(Duration.ofSeconds(3)).untilAsserted {
            assertThat(failed()).isZero()
            assertThat(lastSuccess()).isEqualTo(successEpoch())
        }
    }

    @ParameterizedTest
    @CsvSource("FAILED, 1", "ABANDONED, 1", "STOPPED, 0")
    fun `최근 완료 실행의 상태로 실패 여부를 판단한다`(status: String, expected: Double) {
        insert("COMPLETED")
        insert(status, offset = 2)

        await().atMost(Duration.ofSeconds(3)).untilAsserted {
            assertThat(failed()).isEqualTo(expected)
            assertThat(lastSuccess()).isEqualTo(successEpoch())
        }
    }

    @Test
    fun `실행 중인 재시도 동안에는 실패가 남고 성공하면 해소된다`() {
        insert("COMPLETED")
        insert("FAILED", offset = 2)
        val retryStartedAt = startedAt(4)

        BatchJobMetricsFixture.insertExecution(dataSource, JOB_NAME, "STARTED", retryStartedAt, endedAt = null)

        await().atMost(Duration.ofSeconds(3)).untilAsserted {
            assertThat(failed()).isEqualTo(1.0)
            assertThat(lastSuccess()).isEqualTo(successEpoch())
        }

        insert("COMPLETED", offset = 6)

        await().atMost(Duration.ofSeconds(3)).untilAsserted {
            assertThat(failed()).isZero()
            assertThat(lastSuccess()).isEqualTo(successEpoch(6))
        }
    }

    @Test
    fun `완료 이력이 없거나 감시 목록에 없는 배치는 지표가 없다`() {
        BatchJobMetricsFixture.insertExecution(dataSource, "dailySettlementJob", "FAILED", startedAt())
        BatchJobMetricsFixture.insertExecution(dataSource, "reconcileSeatSnapshotJob", "STARTED", startedAt(), endedAt = null)

        insert("COMPLETED")

        await().atMost(Duration.ofSeconds(3)).untilAsserted {
            assertThat(lastSuccess()).isEqualTo(successEpoch())
            assertThat(failed("reconcileSeatSnapshotJob")).isNull()
            assertThat(lastSuccess("reconcileSeatSnapshotJob")).isNull()
            assertThat(failed("dailySettlementJob")).isNull()
            assertThat(lastSuccess("dailySettlementJob")).isNull()
        }
    }

    private fun insert(status: String, offset: Long = 0) =
        BatchJobMetricsFixture.insertExecution(dataSource, JOB_NAME, status, startedAt(offset))

    private fun startedAt(offset: Long = 0) = LocalDateTime.ofInstant(clock.instant().plusSeconds(offset), ZoneId.systemDefault())

    private fun successEpoch(offset: Long = 0) = clock.instant().plusSeconds(offset + 1).epochSecond.toDouble()

    private fun failed(jobName: String = JOB_NAME) = registry.find("ticketing.batch.job.failed").tag("job", jobName).gauge()?.value()

    private fun lastSuccess(jobName: String = JOB_NAME) =
        registry.find("ticketing.batch.job.last.success").tag("job", jobName).gauge()?.value()

    private companion object {
        const val JOB_NAME = "expirePendingBookingsJob"
        val BASE: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val testSequence = AtomicLong()
    }
}
