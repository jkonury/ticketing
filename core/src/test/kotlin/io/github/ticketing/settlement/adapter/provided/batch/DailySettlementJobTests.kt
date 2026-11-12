package io.github.ticketing.settlement.adapter.provided.batch

import io.github.ticketing.FailingDailySettlementWriter
import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.settlement.application.port.provided.RecordRefundCommand
import io.github.ticketing.settlement.application.port.provided.RecordSaleCommand
import io.github.ticketing.settlement.application.port.required.SettlementCopyCommandPort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import ticketing.jooq.settlement.tables.references.DAILY_SETTLEMENT
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class DailySettlementJobTests(
    private val operator: JobOperator,
    private val dailySettlementJob: Job,
    private val copies: SettlementCopyCommandPort,
    private val dsl: DSLContext,
    private val writer: FailingDailySettlementWriter,
) : IntegrationTestSupport() {
    private fun sale(booking: Long, performance: Long, instant: String, amount: String = "101") {
        copies.insertSaleIfAbsent(
            RecordSaleCommand(booking, performance, 987, 1, BigDecimal(amount), Instant.parse(instant)),
            Instant.EPOCH,
        )
    }

    private fun refund(booking: Long, performance: Long, instant: String) {
        copies.insertRefundIfAbsent(
            RecordRefundCommand(booking, performance, 987, "REFUND", Instant.parse(instant)),
            Instant.EPOCH,
        )
    }

    private fun params(date: String, run: Long = 0) = JobParametersBuilder()
        .addLocalDate("targetDate", LocalDate.parse(date)).addLong("run.id", run).toJobParameters()

    @Test
    fun `KST 경계와 다른 날 확정된 환불을 정산하고 같은 날짜 재실행은 중복되지 않는다`() {
        sale(97001, 97001, "2026-11-10T14:59:59Z")
        sale(97002, 97002, "2026-11-10T15:00:00Z")
        refund(97001, 97001, "2026-11-10T15:00:00Z")
        refund(97003, 97003, "2026-11-10T15:00:00Z")
        val previous = LocalDate.parse("2026-11-10")
        val today = LocalDate.parse("2026-11-11")

        assertThat(operator.start(dailySettlementJob, params(previous.toString())).status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(operator.start(dailySettlementJob, params(today.toString())).status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(operator.start(dailySettlementJob, params(today.toString(), 1)).status).isEqualTo(BatchStatus.COMPLETED)

        val rows = dsl.selectFrom(DAILY_SETTLEMENT).where(DAILY_SETTLEMENT.PERFORMANCE_ID.between(97001L, 97003L)).fetch()
        assertThat(rows).hasSize(3)
        val old = rows.single { it.settlementDate == previous }
        assertThat(old.saleAmount).isEqualByComparingTo("101")
        assertThat(old.refundAmount).isEqualByComparingTo("0")
        val refunded = rows.single { it.settlementDate == today && it.performanceId == 97001L }
        assertThat(refunded.netAmount).isEqualByComparingTo("-101")
        assertThat(refunded.feeAmount).isEqualByComparingTo("-10")
        assertThat(refunded.payoutAmount).isEqualByComparingTo("-91")
        assertThat(rows.single { it.performanceId == 97002L }.saleAmount).isEqualByComparingTo("101")
    }

    @Test
    fun `실패한 두 번째 청크는 같은 파라미터로 재시작한다`() {
        sale(97101, 97101, "2027-01-10T00:00:00Z")
        sale(97102, 97102, "2027-01-10T00:00:00Z")
        writer.failingPerformance = 97102
        writer.failNext.set(true)
        val parameters = params("2027-01-10")

        assertThat(operator.start(dailySettlementJob, parameters).status).isEqualTo(BatchStatus.FAILED)
        assertThat(operator.start(dailySettlementJob, parameters).status).isEqualTo(BatchStatus.COMPLETED)

        assertThat(dsl.fetchCount(DAILY_SETTLEMENT, DAILY_SETTLEMENT.PERFORMANCE_ID.between(97101L, 97102L))).isEqualTo(2)
    }

    @Test
    fun `대상 날짜가 없으면 실행하지 않는다`() {
        assertThatThrownBy { operator.start(dailySettlementJob, JobParametersBuilder().toJobParameters()) }
            .hasMessageContaining("targetDate")
    }
}
