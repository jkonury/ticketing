package io.github.ticketing.catalog.query

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import ticketing.jooq.catalog.tables.references.PERFORMANCE
import ticketing.jooq.catalog.tables.references.PERFORMANCE_PRICE
import ticketing.jooq.catalog.tables.references.SCHEDULE
import ticketing.jooq.catalog.tables.references.VENUE
import ticketing.jooq.catalog.tables.references.VENUE_SEAT
import java.math.BigDecimal

@Repository
class CatalogQueryRepository(private val dsl: DSLContext) {

    /** ID 내림차순 커서 페이지. 다음 페이지 존재 여부를 알기 위해 1건 더 읽는다. */
    fun findPerformances(cursor: Long?, size: Int): PerformancePage {
        val firstStartsAt = DSL.field(
            DSL.select(DSL.min(SCHEDULE.STARTS_AT)).from(SCHEDULE).where(SCHEDULE.PERFORMANCE_ID.eq(PERFORMANCE.ID)),
        )

        val rows = dsl.select(PERFORMANCE.ID, PERFORMANCE.TITLE, VENUE.NAME, firstStartsAt)
            .from(PERFORMANCE)
            .join(VENUE).on(VENUE.ID.eq(PERFORMANCE.VENUE_ID))
            .where(if (cursor != null) PERFORMANCE.ID.lt(cursor) else DSL.noCondition())
            .orderBy(PERFORMANCE.ID.desc())
            .limit(size + 1)
            .fetch {
                PerformanceSummary(
                    id = requireNotNull(it[PERFORMANCE.ID]),
                    title = requireNotNull(it[PERFORMANCE.TITLE]),
                    venueName = requireNotNull(it[VENUE.NAME]),
                    firstStartsAt = it[firstStartsAt]?.toInstant(),
                )
            }

        val items = rows.take(size)
        return PerformancePage(items, if (rows.size > size) items.last().id else null)
    }

    fun findPerformanceDetail(id: Long): PerformanceDetail? {
        val head = dsl.select(PERFORMANCE.ID, PERFORMANCE.TITLE, PERFORMANCE.DESCRIPTION, PERFORMANCE.VENUE_ID, VENUE.NAME)
            .from(PERFORMANCE)
            .join(VENUE).on(VENUE.ID.eq(PERFORMANCE.VENUE_ID))
            .where(PERFORMANCE.ID.eq(id))
            .fetchOne() ?: return null

        val prices = dsl.select(PERFORMANCE_PRICE.GRADE, PERFORMANCE_PRICE.PRICE)
            .from(PERFORMANCE_PRICE)
            .where(PERFORMANCE_PRICE.PERFORMANCE_ID.eq(id))
            .orderBy(PERFORMANCE_PRICE.PRICE.desc())
            .fetch {
                GradePrice(requireNotNull(it[PERFORMANCE_PRICE.GRADE]), BigDecimal.valueOf(requireNotNull(it[PERFORMANCE_PRICE.PRICE])))
            }

        val schedules = dsl.select(SCHEDULE.ID, SCHEDULE.STARTS_AT)
            .from(SCHEDULE)
            .where(SCHEDULE.PERFORMANCE_ID.eq(id))
            .orderBy(SCHEDULE.STARTS_AT.asc())
            .fetch { ScheduleSummary(requireNotNull(it[SCHEDULE.ID]), requireNotNull(it[SCHEDULE.STARTS_AT]).toInstant()) }

        return PerformanceDetail(
            id = requireNotNull(head[PERFORMANCE.ID]),
            title = requireNotNull(head[PERFORMANCE.TITLE]),
            description = requireNotNull(head[PERFORMANCE.DESCRIPTION]),
            venueId = requireNotNull(head[PERFORMANCE.VENUE_ID]),
            venueName = requireNotNull(head[VENUE.NAME]),
            prices = prices,
            schedules = schedules,
        )
    }

    /** 회차가 없으면 null. 좌석이 있어도 회차가 없는 경우와 구분하려고 회차를 먼저 확인한다. */
    fun findSeatLayout(scheduleId: Long): SeatLayout? {
        val exists = dsl.fetchExists(SCHEDULE, SCHEDULE.ID.eq(scheduleId))
        if (!exists) return null

        val seats = dsl.select(
            VENUE_SEAT.ID,
            VENUE_SEAT.SECTION,
            VENUE_SEAT.ROW_LABEL,
            VENUE_SEAT.SEAT_NUMBER,
            VENUE_SEAT.GRADE,
            PERFORMANCE_PRICE.PRICE,
        )
            .from(SCHEDULE)
            .join(PERFORMANCE).on(PERFORMANCE.ID.eq(SCHEDULE.PERFORMANCE_ID))
            .join(VENUE_SEAT).on(VENUE_SEAT.VENUE_ID.eq(PERFORMANCE.VENUE_ID))
            .join(
                PERFORMANCE_PRICE,
            ).on(PERFORMANCE_PRICE.PERFORMANCE_ID.eq(PERFORMANCE.ID).and(PERFORMANCE_PRICE.GRADE.eq(VENUE_SEAT.GRADE)))
            .where(SCHEDULE.ID.eq(scheduleId))
            .orderBy(VENUE_SEAT.SECTION, VENUE_SEAT.ROW_LABEL, VENUE_SEAT.SEAT_NUMBER)
            .fetch {
                SeatView(
                    seatId = requireNotNull(it[VENUE_SEAT.ID]),
                    section = requireNotNull(it[VENUE_SEAT.SECTION]),
                    row = requireNotNull(it[VENUE_SEAT.ROW_LABEL]),
                    number = requireNotNull(it[VENUE_SEAT.SEAT_NUMBER]),
                    grade = requireNotNull(it[VENUE_SEAT.GRADE]),
                    // numeric(12,0)은 jOOQ가 Long으로 생성한다
                    price = BigDecimal.valueOf(requireNotNull(it[PERFORMANCE_PRICE.PRICE])),
                )
            }
        return SeatLayout(seats)
    }
}
