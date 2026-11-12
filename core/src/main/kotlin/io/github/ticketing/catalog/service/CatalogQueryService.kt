package io.github.ticketing.catalog.service

import io.github.ticketing.catalog.config.CatalogCaches
import io.github.ticketing.catalog.query.CatalogQueryRepository
import io.github.ticketing.catalog.query.PerformanceDetail
import io.github.ticketing.catalog.query.PerformancePage
import io.github.ticketing.catalog.query.ScheduleDetail
import io.github.ticketing.catalog.query.SeatLayout
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CatalogQueryService(private val queries: CatalogQueryRepository) {

    @Transactional(readOnly = true)
    fun schedule(id: Long): ScheduleDetail = queries.findSchedule(id) ?: throw ScheduleNotFoundException()

    @Transactional(readOnly = true)
    fun performances(cursor: Long?, size: Int): PerformancePage = queries.findPerformances(cursor, size.coerceIn(1, MAX_PAGE_SIZE))

    @Transactional(readOnly = true)
    @Cacheable(CatalogCaches.PERFORMANCE, key = "#id")
    fun performance(id: Long): PerformanceDetail = queries.findPerformanceDetail(id) ?: throw PerformanceNotFoundException()

    @Transactional(readOnly = true)
    @Cacheable(CatalogCaches.SCHEDULE_SEATS, key = "#scheduleId")
    fun seats(scheduleId: Long): SeatLayout = queries.findSeatLayout(scheduleId) ?: throw ScheduleNotFoundException()

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
    }
}
