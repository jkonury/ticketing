package io.github.ticketing.booking.adapter.required.jooq

import io.github.ticketing.booking.application.port.provided.AdminBookingFilter
import io.github.ticketing.booking.application.port.provided.AdminBookingPage
import io.github.ticketing.booking.application.port.provided.AdminBookingSummary
import io.github.ticketing.booking.application.port.required.AdminBookingQueryPort
import io.github.ticketing.booking.domain.BookingStatus
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import ticketing.jooq.booking.tables.references.SCHEDULE
import java.math.BigDecimal
import java.time.ZoneOffset
import ticketing.jooq.booking.tables.references.BOOKING_ as BOOKING

@Repository
internal class AdminBookingQueryJooqAdapter(private val dsl: DSLContext) : AdminBookingQueryPort {
    override fun findBookings(filter: AdminBookingFilter): AdminBookingPage {
        val conditions = listOfNotNull(
            filter.memberId?.let(BOOKING.MEMBER_ID::eq),
            filter.scheduleId?.let(BOOKING.SCHEDULE_ID::eq),
            filter.performanceId?.let(SCHEDULE.PERFORMANCE_ID::eq),
            filter.status?.let { BOOKING.STATUS.eq(it.name) },
            filter.createdFrom?.let { BOOKING.CREATED_AT.ge(it.atOffset(ZoneOffset.UTC)) },
            filter.createdTo?.let { BOOKING.CREATED_AT.le(it.atOffset(ZoneOffset.UTC)) },
            filter.cursor?.let(BOOKING.ID::lt),
        )

        val rows = dsl.select(
            BOOKING.ID,
            BOOKING.MEMBER_ID,
            BOOKING.SCHEDULE_ID,
            SCHEDULE.PERFORMANCE_TITLE,
            BOOKING.STATUS,
            BOOKING.TOTAL_AMOUNT,
            BOOKING.CREATED_AT,
        )
            .from(BOOKING)
            .join(SCHEDULE).on(SCHEDULE.SCHEDULE_ID.eq(BOOKING.SCHEDULE_ID))
            .where(conditions)
            .orderBy(BOOKING.ID.desc())
            .limit(filter.size + 1)
            .fetch {
                AdminBookingSummary(
                    bookingId = requireNotNull(it[BOOKING.ID]),
                    memberId = requireNotNull(it[BOOKING.MEMBER_ID]),
                    scheduleId = requireNotNull(it[BOOKING.SCHEDULE_ID]),
                    performanceTitle = requireNotNull(it[SCHEDULE.PERFORMANCE_TITLE]),
                    status = BookingStatus.valueOf(requireNotNull(it[BOOKING.STATUS])),
                    totalAmount = BigDecimal.valueOf(requireNotNull(it[BOOKING.TOTAL_AMOUNT])),
                    createdAt = requireNotNull(it[BOOKING.CREATED_AT]).toInstant(),
                )
            }

        val items = rows.take(filter.size)
        val nextCursor = if (rows.size > filter.size) items.last().bookingId else null
        return AdminBookingPage(items, nextCursor)
    }
}
