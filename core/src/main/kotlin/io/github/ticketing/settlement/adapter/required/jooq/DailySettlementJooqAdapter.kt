package io.github.ticketing.settlement.adapter.required.jooq

import io.github.ticketing.settlement.application.port.required.DailySettlementCommandPort
import io.github.ticketing.settlement.application.port.required.PerformanceTotals
import io.github.ticketing.settlement.application.port.required.SettlementQueryPort
import io.github.ticketing.settlement.domain.DailySettlement
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import ticketing.jooq.settlement.tables.references.BOOKING_REFUND
import ticketing.jooq.settlement.tables.references.BOOKING_SALE
import ticketing.jooq.settlement.tables.references.DAILY_SETTLEMENT
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@Repository
class DailySettlementJooqAdapter(private val dsl: DSLContext) :
    SettlementQueryPort,
    DailySettlementCommandPort {
    override fun findPerformanceTotals(date: LocalDate, afterPerformanceId: Long?, limit: Int): List<PerformanceTotals> {
        val start = date.atStartOfDay(ZoneId.of("Asia/Seoul")).toOffsetDateTime()
        val end = date.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toOffsetDateTime()
        val sale = dsl.select(
            BOOKING_SALE.PERFORMANCE_ID,
            BOOKING_SALE.ORGANIZER_ID,
            DSL.inline(1).`as`("sale_count"),
            BOOKING_SALE.AMOUNT.cast(BigDecimal::class.java).`as`("sale_amount"),
            DSL.inline(0).`as`("refund_count"),
            DSL.inline(BigDecimal.ZERO).`as`("refund_amount"),
        ).from(BOOKING_SALE).where(BOOKING_SALE.CONFIRMED_AT.ge(start).and(BOOKING_SALE.CONFIRMED_AT.lt(end)))
        val refund = dsl.select(
            BOOKING_REFUND.PERFORMANCE_ID,
            BOOKING_REFUND.ORGANIZER_ID,
            DSL.inline(0),
            DSL.inline(BigDecimal.ZERO),
            DSL.inline(1),
            BOOKING_SALE.AMOUNT.cast(BigDecimal::class.java),
        ).from(BOOKING_REFUND).join(BOOKING_SALE).on(BOOKING_REFUND.BOOKING_ID.eq(BOOKING_SALE.BOOKING_ID))
            .where(BOOKING_REFUND.CANCELLED_AT.ge(start).and(BOOKING_REFUND.CANCELLED_AT.lt(end)))
        val events = sale.unionAll(refund).asTable("events")
        val performance = requireNotNull(events.field(BOOKING_SALE.PERFORMANCE_ID))
        val organizer = requireNotNull(events.field(BOOKING_SALE.ORGANIZER_ID))
        val saleCount = DSL.sum(requireNotNull(events.field("sale_count", Int::class.java)))
        val saleAmount = DSL.sum(requireNotNull(events.field("sale_amount", BigDecimal::class.java)))
        val refundCount = DSL.sum(requireNotNull(events.field("refund_count", Int::class.java)))
        val refundAmount = DSL.sum(requireNotNull(events.field("refund_amount", BigDecimal::class.java)))
        return dsl.select(performance, organizer, saleCount, saleAmount, refundCount, refundAmount)
            .from(events).where(if (afterPerformanceId == null) DSL.noCondition() else performance.gt(afterPerformanceId))
            .groupBy(performance, organizer).orderBy(performance).limit(limit)
            .fetch { row ->
                PerformanceTotals(
                    requireNotNull(row.value1()),
                    requireNotNull(row.value2()),
                    requireNotNull(row.value3()).toInt(),
                    requireNotNull(row.value4()),
                    requireNotNull(row.value5()).toInt(),
                    requireNotNull(row.value6()),
                )
            }
    }

    override fun upsertAll(settlements: List<DailySettlement>) {
        settlements.forEach { settlement ->
            dsl.insertInto(DAILY_SETTLEMENT)
                .set(DAILY_SETTLEMENT.SETTLEMENT_DATE, settlement.date)
                .set(DAILY_SETTLEMENT.PERFORMANCE_ID, settlement.performanceId)
                .set(DAILY_SETTLEMENT.ORGANIZER_ID, settlement.organizerId)
                .set(DAILY_SETTLEMENT.SALE_COUNT, settlement.saleCount)
                .set(DAILY_SETTLEMENT.SALE_AMOUNT, settlement.saleAmount)
                .set(DAILY_SETTLEMENT.REFUND_COUNT, settlement.refundCount)
                .set(DAILY_SETTLEMENT.REFUND_AMOUNT, settlement.refundAmount)
                .set(DAILY_SETTLEMENT.NET_AMOUNT, settlement.netAmount)
                .set(DAILY_SETTLEMENT.FEE_RATE, settlement.feeRate.value)
                .set(DAILY_SETTLEMENT.FEE_AMOUNT, settlement.feeAmount)
                .set(DAILY_SETTLEMENT.PAYOUT_AMOUNT, settlement.payoutAmount)
                .set(DAILY_SETTLEMENT.CALCULATED_AT, settlement.calculatedAt.atOffset(ZoneOffset.UTC))
                .onConflict(DAILY_SETTLEMENT.SETTLEMENT_DATE, DAILY_SETTLEMENT.PERFORMANCE_ID).doUpdate()
                .set(DAILY_SETTLEMENT.ORGANIZER_ID, settlement.organizerId)
                .set(DAILY_SETTLEMENT.SALE_COUNT, settlement.saleCount)
                .set(DAILY_SETTLEMENT.SALE_AMOUNT, settlement.saleAmount)
                .set(DAILY_SETTLEMENT.REFUND_COUNT, settlement.refundCount)
                .set(DAILY_SETTLEMENT.REFUND_AMOUNT, settlement.refundAmount)
                .set(DAILY_SETTLEMENT.NET_AMOUNT, settlement.netAmount)
                .set(DAILY_SETTLEMENT.FEE_RATE, settlement.feeRate.value)
                .set(DAILY_SETTLEMENT.FEE_AMOUNT, settlement.feeAmount)
                .set(DAILY_SETTLEMENT.PAYOUT_AMOUNT, settlement.payoutAmount)
                .set(DAILY_SETTLEMENT.CALCULATED_AT, settlement.calculatedAt.atOffset(ZoneOffset.UTC))
                .execute()
        }
    }
}
