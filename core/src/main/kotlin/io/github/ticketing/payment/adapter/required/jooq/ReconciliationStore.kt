package io.github.ticketing.payment.adapter.required.jooq

import io.github.ticketing.payment.application.port.required.PgTransaction
import io.github.ticketing.payment.domain.InternalPaymentRecord
import io.github.ticketing.payment.domain.MismatchType
import io.github.ticketing.payment.domain.PaymentStatus
import io.github.ticketing.payment.domain.PgTransactionRecord
import io.github.ticketing.payment.domain.ReconciliationPgStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import ticketing.jooq.payment.tables.references.PG_TRANSACTION_SNAPSHOT
import ticketing.jooq.payment.tables.references.RECONCILIATION_MISMATCH
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import ticketing.jooq.payment.tables.references.PAYMENT_ as PAYMENT

data class ReconciliationRow(val orderId: Long, val internal: InternalPaymentRecord?, val pg: PgTransactionRecord?)
data class ReconciliationGroup(val orderId: Long, val rows: List<ReconciliationRow>)
data class ReconciliationResult(val row: ReconciliationRow, val types: List<MismatchType>)

@Repository
class ReconciliationStore(private val dsl: DSLContext, private val clock: Clock) {
    fun replaceSnapshots(date: LocalDate, transactions: List<PgTransaction>) {
        dsl.deleteFrom(PG_TRANSACTION_SNAPSHOT).where(PG_TRANSACTION_SNAPSHOT.TARGET_DATE.eq(date)).execute()
        val fetchedAt = clock.instant().atOffset(ZoneOffset.UTC)
        transactions.forEach { transaction ->
            dsl.insertInto(PG_TRANSACTION_SNAPSHOT)
                .set(PG_TRANSACTION_SNAPSHOT.TARGET_DATE, date)
                .set(PG_TRANSACTION_SNAPSHOT.TRANSACTION_ID, transaction.transactionId)
                .set(PG_TRANSACTION_SNAPSHOT.ORDER_ID, transaction.orderId)
                .set(PG_TRANSACTION_SNAPSHOT.AMOUNT, transaction.amount)
                .set(PG_TRANSACTION_SNAPSHOT.STATUS, transaction.status.name)
                .set(PG_TRANSACTION_SNAPSHOT.REQUESTED_AT, transaction.requestedAt.atOffset(ZoneOffset.UTC))
                .set(PG_TRANSACTION_SNAPSHOT.APPROVED_AT, transaction.approvedAt?.atOffset(ZoneOffset.UTC))
                .set(PG_TRANSACTION_SNAPSHOT.REFUNDED_AT, transaction.refundedAt?.atOffset(ZoneOffset.UTC))
                .set(PG_TRANSACTION_SNAPSHOT.FETCHED_AT, fetchedAt).execute()
        }
    }

    fun clear(date: LocalDate) {
        dsl.deleteFrom(RECONCILIATION_MISMATCH).where(RECONCILIATION_MISMATCH.TARGET_DATE.eq(date)).execute()
    }

    fun find(date: LocalDate, after: Long?, limit: Int): List<ReconciliationGroup> {
        val start = date.atStartOfDay(ZoneId.of("Asia/Seoul")).toOffsetDateTime()
        val end = date.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toOffsetDateTime()
        val payments = dsl.selectFrom(PAYMENT).where(PAYMENT.REQUESTED_AT.ge(start).and(PAYMENT.REQUESTED_AT.lt(end))).asTable("payments")
        val snapshots = dsl.selectFrom(PG_TRANSACTION_SNAPSHOT).where(PG_TRANSACTION_SNAPSHOT.TARGET_DATE.eq(date)).asTable("snapshots")
        val paymentOrder = requireNotNull(payments.field(PAYMENT.ORDER_ID))
        val pgOrder = requireNotNull(snapshots.field(PG_TRANSACTION_SNAPSHOT.ORDER_ID))
        val order = DSL.coalesce(paymentOrder, pgOrder)
        val paymentId = requireNotNull(payments.field(PAYMENT.ID))
        val paymentStatus = requireNotNull(payments.field(PAYMENT.STATUS))
        val paymentAmount = requireNotNull(payments.field(PAYMENT.AMOUNT))
        val paymentTransaction = requireNotNull(payments.field(PAYMENT.PG_TRANSACTION_ID))
        val pgTransaction = requireNotNull(snapshots.field(PG_TRANSACTION_SNAPSHOT.TRANSACTION_ID))
        val pgStatus = requireNotNull(snapshots.field(PG_TRANSACTION_SNAPSHOT.STATUS))
        val pgAmount = requireNotNull(snapshots.field(PG_TRANSACTION_SNAPSHOT.AMOUNT))
        val joined = payments.fullOuterJoin(snapshots).on(paymentOrder.eq(pgOrder))
        val keys = dsl.selectDistinct(order).from(joined)
            .where(if (after == null) DSL.noCondition() else order.gt(after)).orderBy(order).limit(limit).fetch(order)
        if (keys.isEmpty()) return emptyList()
        val rows = dsl.select(order, paymentId, paymentStatus, paymentAmount, paymentTransaction, pgTransaction, pgStatus, pgAmount)
            .from(joined).where(order.`in`(keys)).orderBy(order, pgTransaction)
            .fetch { row ->
                val internal = row[paymentId]?.let { id ->
                    InternalPaymentRecord(
                        id,
                        PaymentStatus.valueOf(requireNotNull(row[paymentStatus])),
                        requireNotNull(row[paymentAmount]).toBigDecimal(),
                        row[paymentTransaction],
                    )
                }
                val pg = row[pgTransaction]?.let { id ->
                    PgTransactionRecord(
                        id,
                        ReconciliationPgStatus.valueOf(requireNotNull(row[pgStatus])),
                        requireNotNull(row[pgAmount]),
                    )
                }
                ReconciliationRow(requireNotNull(row[order]), internal, pg)
            }
        return rows.groupBy { it.orderId }.map { (orderId, group) -> ReconciliationGroup(orderId, group) }
    }

    fun insert(date: LocalDate, results: List<ReconciliationResult>) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        results.forEach { result ->
            val internal = result.row.internal
            val pg = result.row.pg
            result.types.forEach { type ->
                dsl.insertInto(RECONCILIATION_MISMATCH)
                    .set(RECONCILIATION_MISMATCH.TARGET_DATE, date)
                    .set(RECONCILIATION_MISMATCH.ORDER_ID, result.row.orderId)
                    .set(RECONCILIATION_MISMATCH.MISMATCH_TYPE, type.name)
                    .set(RECONCILIATION_MISMATCH.PG_TRANSACTION_ID, pg?.transactionId)
                    .set(RECONCILIATION_MISMATCH.PG_STATUS, pg?.status?.name)
                    .set(RECONCILIATION_MISMATCH.PG_AMOUNT, pg?.amount)
                    .set(RECONCILIATION_MISMATCH.INTERNAL_PAYMENT_ID, internal?.id)
                    .set(RECONCILIATION_MISMATCH.INTERNAL_STATUS, internal?.status?.name)
                    .set(RECONCILIATION_MISMATCH.INTERNAL_AMOUNT, internal?.amount)
                    .set(RECONCILIATION_MISMATCH.DETECTED_AT, now)
                    .onConflictDoNothing().execute()
            }
        }
    }

    fun counts(date: LocalDate): Map<String, Int> = dsl.select(RECONCILIATION_MISMATCH.MISMATCH_TYPE, DSL.count())
        .from(RECONCILIATION_MISMATCH).where(RECONCILIATION_MISMATCH.TARGET_DATE.eq(date))
        .groupBy(RECONCILIATION_MISMATCH.MISMATCH_TYPE).fetch().associate { requireNotNull(it.value1()) to it.value2() }
}
