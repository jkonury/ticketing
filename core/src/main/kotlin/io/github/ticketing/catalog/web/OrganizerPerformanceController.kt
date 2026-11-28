package io.github.ticketing.catalog.web

import io.github.ticketing.catalog.config.PosterStorageProperties
import io.github.ticketing.catalog.domain.PerformanceStatus
import io.github.ticketing.catalog.service.CatalogQueryService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/organizer/performances")
@PreAuthorize("hasRole('ORGANIZER')")
class OrganizerPerformanceController(private val queries: CatalogQueryService, private val posters: PosterStorageProperties) {

    @GetMapping("/{id}")
    fun ownedPerformance(@PathVariable id: Long, authentication: Authentication): OrganizerPerformanceResponse {
        val detail = queries.ownedPerformance(id, authentication.name.toLong())
        return OrganizerPerformanceResponse(detail.performance.toResponse(posters), detail.status)
    }
}

data class OrganizerPerformanceResponse(val performance: PerformanceDetailResponse, val status: PerformanceStatus)
