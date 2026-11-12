package io.github.ticketing.payment.adapter.provided.batch

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.TestPgTransactions
import io.github.ticketing.payment.application.port.required.PgTransaction
import io.github.ticketing.payment.application.port.required.PgTransactionStatus
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import ticketing.jooq.payment.tables.references.RECONCILIATION_MISMATCH
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import ticketing.jooq.payment.tables.references.PAYMENT_ as PAYMENT

class PaymentReconciliationJobTests(
    private val operator: JobOperator,
    private val paymentReconciliationJob: Job,
    private val pg: TestPgTransactions,
    private val dsl: DSLContext,
) : IntegrationTestSupport() {
    private val date = LocalDate.parse("2030-05-11")
    private val instant = Instant.parse("2030-05-10T15:00:00Z")

    private fun payment(order: Long, status: String = "REQUESTED", transaction: String? = null) {
        dsl.insertInto(PAYMENT)
            .set(PAYMENT.ORDER_ID, order).set(PAYMENT.MEMBER_ID, 1L).set(PAYMENT.AMOUNT, 100L)
            .set(PAYMENT.STATUS, status).set(PAYMENT.IDEMPOTENCY_KEY, "reconcile-$order")
            .set(PAYMENT.PG_TRANSACTION_ID, transaction).set(PAYMENT.REQUESTED_AT, instant.atOffset(ZoneOffset.UTC)).execute()
    }

    private fun transaction(order: Long, status: PgTransactionStatus = PgTransactionStatus.PENDING, amount: Long = 100) =
        PgTransaction("tx-$order", order, BigDecimal.valueOf(amount), status, instant, null, null)

    private fun params(target: LocalDate, run: Long = 0) = JobParametersBuilder()
        .addLocalDate("targetDate", target).addLong("run.id", run).toJobParameters()

    private fun mismatches(target: LocalDate): Set<Pair<Long?, String?>> = dsl.select(
        RECONCILIATION_MISMATCH.ORDER_ID,
        RECONCILIATION_MISMATCH.MISMATCH_TYPE,
    ).from(RECONCILIATION_MISMATCH).where(RECONCILIATION_MISMATCH.TARGET_DATE.eq(target))
        .fetch().map { it.value1() to it.value2() }.toSet()

    @Test
    fun `일치와 모든 불일치를 기록하고 재실행 시 새 결과로 교체한다`() {
        payment(99101, transaction = "tx-99101")
        payment(99102)
        payment(99103, transaction = "wrong")
        pg.fail = false
        pg.transactions = listOf(transaction(99101), transaction(99103, PgTransactionStatus.FAILED, 200), transaction(99104))
        val expected = setOf(
            99102L to "MISSING_PG",
            99103L to "STATUS_MISMATCH",
            99103L to "AMOUNT_MISMATCH",
            99103L to "TRANSACTION_ID_MISMATCH",
            99104L to "MISSING_INTERNAL",
        )

        assertThat(operator.start(paymentReconciliationJob, params(date)).status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(mismatches(date)).isEqualTo(expected)
        assertThat(operator.start(paymentReconciliationJob, params(date, 1)).status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(mismatches(date)).isEqualTo(expected)
        pg.transactions = listOf(transaction(99101), transaction(99102), transaction(99103))
        assertThat(operator.start(paymentReconciliationJob, params(date, 2)).status).isEqualTo(BatchStatus.COMPLETED)

        assertThat(mismatches(date)).containsExactly(99103L to "TRANSACTION_ID_MISMATCH")
    }

    @Test
    fun `같은 주문의 여러 거래를 페이지 경계에서도 모두 비교한다`() {
        val target = date.plusDays(2)
        val timestamp = instant.plusSeconds(2 * 86400)
        val transactions = (1L..99L).map { transaction(99200 + it).copy(requestedAt = timestamp) }
        val first = transaction(99300).copy(transactionId = "first", requestedAt = timestamp)
        val second = transaction(99300).copy(transactionId = "second", requestedAt = timestamp)
        pg.fail = false
        pg.transactions = transactions + first + second + transaction(99301).copy(requestedAt = timestamp)

        assertThat(operator.start(paymentReconciliationJob, params(target)).status).isEqualTo(BatchStatus.COMPLETED)

        assertThat(mismatches(target)).hasSize(101).contains(99300L to "MISSING_INTERNAL", 99301L to "MISSING_INTERNAL")
    }

    @Test
    fun `PG 조회 실패를 고친 뒤 같은 실행을 재시작한다`() {
        val target = date.plusDays(1)
        pg.fail = true
        pg.transactions = emptyList()
        val parameters = params(target)

        assertThat(operator.start(paymentReconciliationJob, parameters).status).isEqualTo(BatchStatus.FAILED)
        pg.fail = false
        assertThat(operator.start(paymentReconciliationJob, parameters).status).isEqualTo(BatchStatus.COMPLETED)
    }
}
