package io.github.ticketing.catalog.web

import io.github.ticketing.catalog.query.PerformanceDetail
import io.github.ticketing.catalog.query.PerformancePage
import io.github.ticketing.catalog.query.SeatView
import io.github.ticketing.catalog.service.CatalogQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class CatalogQueryController(private val service: CatalogQueryService) {

    @GetMapping("/performances")
    fun performances(
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "${CatalogQueryService.DEFAULT_PAGE_SIZE}") size: Int,
    ): PerformancePage = service.performances(cursor, size)

    @GetMapping("/performances/{id}")
    fun performance(@PathVariable id: Long): PerformanceDetail = service.performance(id)

    @GetMapping("/schedules/{id}/seats")
    fun seats(@PathVariable id: Long): List<SeatView> = service.seats(id).seats
}
