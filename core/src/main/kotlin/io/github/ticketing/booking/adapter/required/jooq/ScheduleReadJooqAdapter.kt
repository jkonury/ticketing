package io.github.ticketing.booking.adapter.required.jooq

import io.github.ticketing.booking.application.port.provided.ScheduleAvailability
import io.github.ticketing.booking.application.port.provided.ScheduleSales
import io.github.ticketing.booking.application.port.required.ScheduleReadPort
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import ticketing.jooq.booking.tables.references.BOOKING_SEAT
import ticketing.jooq.booking.tables.references.SCHEDULE
import ticketing.jooq.booking.tables.references.SCHEDULE_SEAT
import ticketing.jooq.booking.tables.references.SOLD_SEAT
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import ticketing.jooq.booking.tables.references.BOOKING_ as BOOKING

@Repository
internal class ScheduleReadJooqAdapter(private val dsl: DSLContext) : ScheduleReadPort {
    private val totalSeats = DSL.field(
        DSL.selectCount().from(SCHEDULE_SEAT).where(SCHEDULE_SEAT.SCHEDULE_ID.eq(SCHEDULE.SCHEDULE_ID)),
    )
    private val soldSeats = DSL.field(
        DSL.selectCount().from(SOLD_SEAT).where(SOLD_SEAT.SCHEDULE_ID.eq(SCHEDULE.SCHEDULE_ID)),
    )

    override fun availability(scheduleIds: List<Long>, now: Instant, paymentCutoff: Instant): List<ScheduleAvailability> {
        val pendingSeats = DSL.field(
            DSL.selectCount().from(BOOKING_SEAT).join(BOOKING).on(BOOKING.ID.eq(BOOKING_SEAT.BOOKING_ID))
                .where(BOOKING.SCHEDULE_ID.eq(SCHEDULE.SCHEDULE_ID)).and(BOOKING.STATUS.eq("PENDING"))
                .and(
                    BOOKING.PAYMENT_REQUESTED_AT.isNull.and(BOOKING.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)))
                        .or(BOOKING.PAYMENT_REQUESTED_AT.isNotNull.and(BOOKING.EXPIRES_AT.gt(paymentCutoff.atOffset(ZoneOffset.UTC)))),
                ),
        )
        return dsl.select(SCHEDULE.SCHEDULE_ID, totalSeats, soldSeats, pendingSeats).from(SCHEDULE)
            .where(SCHEDULE.SCHEDULE_ID.`in`(scheduleIds), SCHEDULE.SALE_ENABLED.eq(true))
            .orderBy(SCHEDULE.SCHEDULE_ID.asc())
            .fetch {
                val total = requireNotNull(it[totalSeats])
                ScheduleAvailability(
                    requireNotNull(it[SCHEDULE.SCHEDULE_ID]),
                    total,
                    (total - requireNotNull(it[soldSeats]) - requireNotNull(it[pendingSeats])).coerceAtLeast(0),
                )
            }
    }

    override fun sales(organizerId: Long): List<ScheduleSales> {
        val amount = DSL.field(
            DSL.select(DSL.coalesce(DSL.sum(BOOKING.TOTAL_AMOUNT), BigDecimal.ZERO)).from(BOOKING)
                .where(BOOKING.SCHEDULE_ID.eq(SCHEDULE.SCHEDULE_ID)).and(BOOKING.STATUS.eq("CONFIRMED")),
        )
        return dsl.select(
            SCHEDULE.PERFORMANCE_ID,
            SCHEDULE.PERFORMANCE_TITLE,
            SCHEDULE.SCHEDULE_ID,
            SCHEDULE.STARTS_AT,
            totalSeats,
            soldSeats,
            amount,
        )
            .from(SCHEDULE).where(SCHEDULE.ORGANIZER_ID.eq(organizerId))
            .orderBy(SCHEDULE.PERFORMANCE_ID.asc(), SCHEDULE.STARTS_AT.asc())
            .fetch {
                ScheduleSales(
                    requireNotNull(it[SCHEDULE.PERFORMANCE_ID]),
                    requireNotNull(it[SCHEDULE.PERFORMANCE_TITLE]),
                    requireNotNull(it[SCHEDULE.SCHEDULE_ID]),
                    requireNotNull(it[SCHEDULE.STARTS_AT]).toInstant(),
                    requireNotNull(it[totalSeats]),
                    requireNotNull(it[soldSeats]),
                    requireNotNull(it[amount]),
                )
            }
    }
}
