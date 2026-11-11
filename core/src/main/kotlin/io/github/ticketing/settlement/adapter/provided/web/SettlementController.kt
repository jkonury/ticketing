package io.github.ticketing.settlement.adapter.provided.web

import io.github.ticketing.settlement.application.port.provided.DailySettlementQuery
import io.github.ticketing.settlement.application.port.provided.InvalidSettlementPeriodException
import io.github.ticketing.settlement.application.port.provided.SettlementQueryUseCase
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.format.DateTimeParseException

@RestController
@RequestMapping("/api/v1/settlements")
@PreAuthorize("hasRole('ORGANIZER')")
class SettlementController(private val queries: SettlementQueryUseCase) {
    /** 조회 기간이 최대 31일이므로 결과는 공연 수의 31배 이하라 페이지네이션을 생략한다. */
    @GetMapping
    fun find(
        authentication: Authentication,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
    ): List<DailySettlementResponse> {
        val query = DailySettlementQuery(authentication.name.toLong(), parseDate(from), parseDate(to))
        return queries.findDailySettlements(query).map(DailySettlementResponse::from)
    }

    private fun parseDate(value: String?): LocalDate = try {
        LocalDate.parse(value ?: throw InvalidSettlementPeriodException())
    } catch (exception: DateTimeParseException) {
        throw InvalidSettlementPeriodException()
    }
}
