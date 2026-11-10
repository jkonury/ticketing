package io.github.ticketing.catalog.web

import io.github.ticketing.catalog.domain.SeatGrade
import io.github.ticketing.catalog.service.CatalogRegistrationService
import jakarta.validation.Valid
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('ORGANIZER')")
class CatalogRegistrationController(
    private val service: CatalogRegistrationService,
) {

    @PostMapping("/venues")
    fun registerVenue(@Valid @RequestBody request: RegisterVenueRequest): ResponseEntity<VenueRegisteredResponse> {
        val seats = request.seats.map { CatalogRegistrationService.SeatSpec(it.section, it.row, it.number, it.grade) }
        val venue = service.registerVenue(CatalogRegistrationService.RegisterVenueCommand(request.name, seats))
        return ResponseEntity.status(HttpStatus.CREATED).body(VenueRegisteredResponse(requireNotNull(venue.id), venue.seats.size))
    }

    @PostMapping("/performances")
    fun registerPerformance(
        authentication: Authentication,
        @Valid @RequestBody request: RegisterPerformanceRequest,
    ): ResponseEntity<IdResponse> {
        val prices = request.prices.map { CatalogRegistrationService.PriceSpec(it.grade, it.price) }
        val performance = service.registerPerformance(
            CatalogRegistrationService.RegisterPerformanceCommand(
                authentication.name.toLong(),
                request.title,
                request.description,
                request.venueId,
                prices,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(IdResponse(requireNotNull(performance.id)))
    }

    @PostMapping("/performances/{performanceId}/schedules")
    fun registerSchedule(
        authentication: Authentication,
        @PathVariable performanceId: Long,
        @Valid @RequestBody request: RegisterScheduleRequest,
    ): ResponseEntity<IdResponse> {
        val schedule = service.registerSchedule(
            CatalogRegistrationService.RegisterScheduleCommand(authentication.name.toLong(), performanceId, request.startsAt),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(IdResponse(requireNotNull(schedule.id)))
    }
}

data class RegisterVenueRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:Valid @field:NotEmpty @field:Size(min = 1, max = 5000) val seats: List<VenueSeatRequest>,
)

data class VenueSeatRequest(
    @field:NotBlank @field:Size(max = 20) val section: String,
    @field:NotBlank @field:Size(max = 10) val row: String,
    @field:Min(1) val number: Int,
    val grade: SeatGrade,
)

data class RegisterPerformanceRequest(
    @field:NotBlank @field:Size(max = 200) val title: String,
    @field:NotBlank val description: String,
    val venueId: Long,
    @field:Valid @field:NotEmpty val prices: List<PriceRequest>,
)

data class PriceRequest(
    val grade: SeatGrade,
    // 컬럼이 numeric(12,0)이므로 원 단위 정수만 받는다
    @field:PositiveOrZero @field:Digits(integer = 12, fraction = 0) val price: BigDecimal,
)

data class RegisterScheduleRequest(val startsAt: Instant)

data class VenueRegisteredResponse(val id: Long, val seatCount: Int)

data class IdResponse(val id: Long)
