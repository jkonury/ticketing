package io.github.ticketing.settlement.adapter.provided.web

import io.github.ticketing.settlement.application.port.provided.DailySettlementView
import java.math.BigDecimal
import java.time.LocalDate

data class DailySettlementResponse(
    val settlementDate: LocalDate,
    val performanceId: Long,
    val saleCount: Int,
    val saleAmount: BigDecimal,
    val refundCount: Int,
    val refundAmount: BigDecimal,
    val netAmount: BigDecimal,
    val feeRate: BigDecimal,
    val feeAmount: BigDecimal,
    val payoutAmount: BigDecimal,
) {
    companion object {
        fun from(view: DailySettlementView) = DailySettlementResponse(
            view.settlementDate, view.performanceId, view.saleCount, view.saleAmount, view.refundCount,
            view.refundAmount, view.netAmount, view.feeRate, view.feeAmount, view.payoutAmount,
        )
    }
}
