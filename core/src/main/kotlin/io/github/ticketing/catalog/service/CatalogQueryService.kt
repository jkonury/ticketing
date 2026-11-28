package io.github.ticketing.catalog.service

import io.github.ticketing.catalog.config.CatalogCaches
import io.github.ticketing.catalog.domain.PerformanceGenre
import io.github.ticketing.catalog.query.CatalogQueryRepository
import io.github.ticketing.catalog.query.OwnedPerformanceDetail
import io.github.ticketing.catalog.query.PerformanceDetail
import io.github.ticketing.catalog.query.PerformancePage
import io.github.ticketing.catalog.query.ScheduleDetail
import io.github.ticketing.catalog.query.SeatLayout
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CatalogQueryService(private val queries: CatalogQueryRepository, private val cached: CachedCatalogViews) {

    @Transactional(readOnly = true)
    fun schedule(id: Long): ScheduleDetail = queries.findSchedule(id) ?: throw ScheduleNotFoundException()

    @Transactional(readOnly = true)
    fun performances(cursor: Long?, size: Int, genre: PerformanceGenre? = null): PerformancePage =
        queries.findPerformances(cursor, size.coerceIn(1, MAX_PAGE_SIZE), genre)

    // 중지 직전에 캐시를 읽거나 채운 요청이 있어도 중지 후 공개 응답에 노출되지 않게 한다
    @Transactional(readOnly = true)
    fun performance(id: Long): PerformanceDetail {
        if (!queries.isPublicPerformance(id)) throw PerformanceNotFoundException()
        return cached.performance(id)
    }

    @Transactional(readOnly = true)
    fun seats(scheduleId: Long): SeatLayout {
        if (!queries.isPublicSchedule(scheduleId)) throw ScheduleNotFoundException()
        return cached.seats(scheduleId)
    }

    @Transactional(readOnly = true)
    fun ownedPerformance(id: Long, organizerId: Long): OwnedPerformanceDetail =
        queries.findOwnedPerformance(id, organizerId) ?: throw PerformanceNotFoundException()

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
    }
}

@Service
class CachedCatalogViews(private val queries: CatalogQueryRepository) {

    @Cacheable(CatalogCaches.PERFORMANCE, key = "#id")
    fun performance(id: Long): PerformanceDetail = queries.findPerformanceDetail(id) ?: throw PerformanceNotFoundException()

    @Cacheable(CatalogCaches.SCHEDULE_SEATS, key = "#scheduleId")
    fun seats(scheduleId: Long): SeatLayout = queries.findSeatLayout(scheduleId) ?: throw ScheduleNotFoundException()
}
