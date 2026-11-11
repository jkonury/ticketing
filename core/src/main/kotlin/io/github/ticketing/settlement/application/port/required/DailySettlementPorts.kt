package io.github.ticketing.settlement.application.port.required

import io.github.ticketing.settlement.application.port.provided.DailySettlementView
import io.github.ticketing.settlement.domain.DailySettlement
import java.math.BigDecimal
import java.time.LocalDate

data class PerformanceTotals(
    val performanceId: Long,
    val organizerId: Long,
    val saleCount: Int,
    val saleAmount: BigDecimal,
    val refundCount: Int,
    val refundAmount: BigDecimal,
)

interface SettlementQueryPort {
    fun findDailySettlements(organizerId: Long, from: LocalDate, to: LocalDate): List<DailySettlementView>

    fun findPerformanceTotals(date: LocalDate, afterPerformanceId: Long?, limit: Int): List<PerformanceTotals>
}

interface DailySettlementCommandPort {
    fun upsertAll(settlements: List<DailySettlement>)
}
