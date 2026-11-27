package io.github.ticketing.settlement.application.service

import io.github.ticketing.settlement.application.port.provided.DailySettlementQuery
import io.github.ticketing.settlement.application.port.provided.DailySettlementView
import io.github.ticketing.settlement.application.port.provided.InvalidSettlementPeriodException
import io.github.ticketing.settlement.application.port.provided.SettlementQueryUseCase
import io.github.ticketing.settlement.application.port.required.SettlementQueryPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.temporal.ChronoUnit

@Service
@Transactional(readOnly = true)
class SettlementQueryService(private val queryPort: SettlementQueryPort) : SettlementQueryUseCase {
    companion object {
        private const val MAX_PERIOD_DAYS_DIFFERENCE = 30L
    }

    override fun findDailySettlements(query: DailySettlementQuery): List<DailySettlementView> {
        val days = ChronoUnit.DAYS.between(query.from, query.to)
        if (days !in 0..MAX_PERIOD_DAYS_DIFFERENCE) throw InvalidSettlementPeriodException()

        return queryPort.findDailySettlements(query.organizerId, query.from, query.to)
    }
}
