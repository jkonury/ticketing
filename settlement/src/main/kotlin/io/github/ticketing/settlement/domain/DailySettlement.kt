package io.github.ticketing.settlement.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

data class FeeRate(val value: BigDecimal) {
    init {
        require(value >= BigDecimal.ZERO && value < BigDecimal.ONE)
    }
}

data class DailySettlement(
    val date: LocalDate,
    val performanceId: Long,
    val organizerId: Long,
    val saleCount: Int,
    val saleAmount: BigDecimal,
    val refundCount: Int,
    val refundAmount: BigDecimal,
    val netAmount: BigDecimal,
    val feeRate: FeeRate,
    val feeAmount: BigDecimal,
    val payoutAmount: BigDecimal,
    val platformDiscountAmount: BigDecimal,
    val calculatedAt: Instant,
)

object DailySettlementCalculator {
    fun settle(
        date: LocalDate,
        performanceId: Long,
        organizerId: Long,
        saleCount: Int,
        saleAmount: BigDecimal,
        refundCount: Int,
        refundAmount: BigDecimal,
        feeRate: FeeRate,
        calculatedAt: Instant,
        platformDiscountAmount: BigDecimal = BigDecimal.ZERO,
    ): DailySettlement {
        val netAmount = saleAmount - refundAmount
        val feeAmount = netAmount.multiply(feeRate.value).setScale(0, RoundingMode.DOWN)
        return DailySettlement(
            date, performanceId, organizerId, saleCount, saleAmount, refundCount, refundAmount,
            netAmount, feeRate, feeAmount, netAmount - feeAmount, platformDiscountAmount, calculatedAt,
        )
    }
}
