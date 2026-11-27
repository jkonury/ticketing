package io.github.ticketing.settlement.application.port.provided

import io.github.ticketing.DomainException
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.LocalDate

data class DailySettlementQuery(val organizerId: Long, val from: LocalDate, val to: LocalDate)

data class DailySettlementView(
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
)

interface SettlementQueryUseCase {
    fun findDailySettlements(query: DailySettlementQuery): List<DailySettlementView>
}

class InvalidSettlementPeriodException :
    DomainException(
        "INVALID_SETTLEMENT_PERIOD",
        HttpStatus.BAD_REQUEST,
        "정산 조회 기간이 올바르지 않습니다.",
    )
