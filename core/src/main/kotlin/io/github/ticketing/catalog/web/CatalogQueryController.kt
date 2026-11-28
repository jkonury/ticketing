package io.github.ticketing.catalog.web

import io.github.ticketing.catalog.config.PosterStorageProperties
import io.github.ticketing.catalog.domain.PerformanceGenre
import io.github.ticketing.catalog.query.GradePrice
import io.github.ticketing.catalog.query.PerformanceDetail
import io.github.ticketing.catalog.query.ScheduleDetail
import io.github.ticketing.catalog.query.ScheduleSummary
import io.github.ticketing.catalog.query.SeatView
import io.github.ticketing.catalog.service.CatalogQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1")
class CatalogQueryController(private val service: CatalogQueryService, private val posters: PosterStorageProperties) {

    @GetMapping("/performances")
    fun performances(
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "${CatalogQueryService.DEFAULT_PAGE_SIZE}") size: Int,
        @RequestParam(required = false) genre: PerformanceGenre?,
    ): PerformancePageResponse {
        val page = service.performances(cursor, size, genre)
        return PerformancePageResponse(
            page.items.map {
                PerformanceSummaryResponse(it.id, it.title, it.venueName, it.firstStartsAt, it.genre, posters.publicUrl(it.posterKey))
            },
            page.nextCursor,
        )
    }

    @GetMapping("/performances/{id}")
    fun performance(@PathVariable id: Long): PerformanceDetailResponse = service.performance(id).toResponse(posters)

    @GetMapping("/schedules/{id}")
    fun schedule(@PathVariable id: Long): ScheduleDetail = service.schedule(id)

    @GetMapping("/schedules/{id}/seats")
    fun seats(@PathVariable id: Long): List<SeatView> = service.seats(id).seats
}

data class PerformancePageResponse(val items: List<PerformanceSummaryResponse>, val nextCursor: Long?)

data class PerformanceSummaryResponse(
    val id: Long,
    val title: String,
    val venueName: String,
    val firstStartsAt: Instant?,
    val genre: PerformanceGenre?,
    val posterUrl: String?,
)

data class PerformanceDetailResponse(
    val id: Long,
    val title: String,
    val description: String,
    val venueId: Long,
    val venueName: String,
    val prices: List<GradePrice>,
    val schedules: List<ScheduleSummary>,
    val genre: PerformanceGenre?,
    val posterUrl: String?,
)

internal fun PerformanceDetail.toResponse(posters: PosterStorageProperties) = PerformanceDetailResponse(
    id, title, description, venueId, venueName, prices, schedules, genre, posters.publicUrl(posterKey),
)
