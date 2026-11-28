package io.github.ticketing.catalog.web

import io.github.ticketing.catalog.domain.PerformanceStatus
import io.github.ticketing.catalog.query.AdminPerformanceFilter
import io.github.ticketing.catalog.query.AdminPerformancePage
import io.github.ticketing.catalog.query.AdminPerformanceQueryRepository
import io.github.ticketing.catalog.service.AdminPerformanceService
import io.github.ticketing.catalog.service.CatalogQueryService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/performances")
@PreAuthorize("hasRole('ADMIN')")
class AdminPerformanceController(private val queries: AdminPerformanceQueryRepository, private val admin: AdminPerformanceService) {

    @GetMapping
    fun adminPerformances(
        @RequestParam(required = false) status: PerformanceStatus?,
        @RequestParam(required = false) organizerId: Long?,
        @RequestParam(required = false) title: String?,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(
            defaultValue = "${CatalogQueryService.DEFAULT_PAGE_SIZE}",
        ) @Min(1) @Max(CatalogQueryService.MAX_PAGE_SIZE.toLong()) size: Int,
    ): AdminPerformancePage = queries.findPerformances(AdminPerformanceFilter(status, organizerId, title), cursor, size)

    @PutMapping("/{id}/suspension")
    fun suspendPerformance(
        @PathVariable id: Long,
        @RequestBody @Valid request: SuspendPerformanceRequest,
        authentication: Authentication,
    ): PerformanceSuspensionResponse =
        PerformanceSuspensionResponse(id, admin.suspendPerformance(authentication.name.toLong(), id, request.reason))

    @DeleteMapping("/{id}/suspension")
    fun resumePerformance(@PathVariable id: Long, authentication: Authentication): PerformanceSuspensionResponse =
        PerformanceSuspensionResponse(id, admin.resume(authentication.name.toLong(), id))

    @PutMapping("/{id}/cancellation")
    fun cancelPerformance(
        @PathVariable id: Long,
        @RequestBody @Valid request: CancelPerformanceRequest,
        authentication: Authentication,
    ): PerformanceCancellationResponse = PerformanceCancellationResponse(
        id,
        admin.cancel(authentication.name.toLong(), id, request.reason),
    )
}

data class SuspendPerformanceRequest(@field:NotBlank @field:Size(max = 200) val reason: String)
data class CancelPerformanceRequest(@field:NotBlank @field:Size(max = 200) val reason: String)

data class PerformanceSuspensionResponse(val id: Long, val status: PerformanceStatus)
data class PerformanceCancellationResponse(val id: Long, val status: PerformanceStatus)
