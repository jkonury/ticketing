package io.github.ticketing.catalog.query

import io.github.ticketing.catalog.domain.PerformanceStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import ticketing.jooq.catalog.tables.references.PERFORMANCE
import ticketing.jooq.catalog.tables.references.SCHEDULE
import ticketing.jooq.catalog.tables.references.VENUE
import java.time.Instant

@Repository
class AdminPerformanceQueryRepository(private val dsl: DSLContext) {

    fun findPerformances(filter: AdminPerformanceFilter, cursor: Long?, size: Int): AdminPerformancePage {
        val conditions = listOfNotNull(
            cursor?.let { PERFORMANCE.ID.lt(it) },
            filter.status?.let { PERFORMANCE.STATUS.eq(it.name) },
            filter.organizerId?.let { PERFORMANCE.ORGANIZER_ID.eq(it) },
            filter.title?.trim()?.takeIf(String::isNotEmpty)?.let { PERFORMANCE.TITLE.containsIgnoreCase(it) },
        )
        val rows = dsl.select(
            PERFORMANCE.ID,
            PERFORMANCE.TITLE,
            PERFORMANCE.ORGANIZER_ID,
            VENUE.NAME,
            PERFORMANCE.STATUS,
            PERFORMANCE.CREATED_AT,
        )
            .from(PERFORMANCE)
            .join(VENUE).on(VENUE.ID.eq(PERFORMANCE.VENUE_ID))
            .where(DSL.and(conditions))
            .orderBy(PERFORMANCE.ID.desc())
            .limit(size + 1)
            .fetch {
                AdminPerformanceSummary(
                    id = requireNotNull(it[PERFORMANCE.ID]),
                    title = requireNotNull(it[PERFORMANCE.TITLE]),
                    organizerId = requireNotNull(it[PERFORMANCE.ORGANIZER_ID]),
                    venueName = requireNotNull(it[VENUE.NAME]),
                    status = PerformanceStatus.valueOf(requireNotNull(it[PERFORMANCE.STATUS])),
                    createdAt = requireNotNull(it[PERFORMANCE.CREATED_AT]).toInstant(),
                )
            }
        val items = rows.take(size)
        return AdminPerformancePage(items, if (rows.size > size) items.last().id else null)
    }

    fun scheduleIds(performanceId: Long): List<Long> = dsl.select(SCHEDULE.ID).from(SCHEDULE)
        .where(SCHEDULE.PERFORMANCE_ID.eq(performanceId))
        .fetch { requireNotNull(it[SCHEDULE.ID]) }
}

data class AdminPerformanceFilter(val status: PerformanceStatus?, val organizerId: Long?, val title: String?)

data class AdminPerformanceSummary(
    val id: Long,
    val title: String,
    val organizerId: Long,
    val venueName: String,
    val status: PerformanceStatus,
    val createdAt: Instant,
)

data class AdminPerformancePage(val items: List<AdminPerformanceSummary>, val nextCursor: Long?)
