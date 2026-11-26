package io.github.ticketing.booking.adapter.provided.web

import io.github.ticketing.booking.application.port.provided.ScheduleReadUseCase
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant

@RestController
class ScheduleReadController(private val reads: ScheduleReadUseCase) {
    @GetMapping("/api/v1/schedules/availability")
    @Operation(operationId = "getScheduleAvailability")
    fun availability(@RequestParam @Size(min = 1, max = 50) scheduleIds: List<Long>): List<AvailabilityResponse> =
        reads.availability(scheduleIds).map { AvailabilityResponse(it.scheduleId, it.totalSeats, it.availableSeats) }

    @GetMapping("/api/v1/organizer/sales")
    @Operation(operationId = "getScheduleSales")
    @PreAuthorize("hasRole('ORGANIZER')")
    fun sales(authentication: Authentication): List<SalesResponse> = reads.sales(authentication.name.toLong()).map {
        SalesResponse(it.performanceId, it.performanceTitle, it.scheduleId, it.startsAt, it.totalSeats, it.soldSeats, it.confirmedAmount)
    }
}

data class AvailabilityResponse(val scheduleId: Long, val totalSeats: Int, val availableSeats: Int)

data class SalesResponse(
    val performanceId: Long,
    val performanceTitle: String,
    val scheduleId: Long,
    val startsAt: Instant,
    val totalSeats: Int,
    val soldSeats: Int,
    val confirmedAmount: BigDecimal,
)
