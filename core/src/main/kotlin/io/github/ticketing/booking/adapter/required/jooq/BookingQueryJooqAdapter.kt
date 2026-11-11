package io.github.ticketing.booking.adapter.required.jooq

import io.github.ticketing.booking.application.port.provided.BookingDetailView
import io.github.ticketing.booking.application.port.provided.BookingPage
import io.github.ticketing.booking.application.port.provided.BookingSeatResult
import io.github.ticketing.booking.application.port.provided.BookingSummaryView
import io.github.ticketing.booking.application.port.required.BookingQueryPort
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import ticketing.jooq.booking.tables.references.BOOKING_SEAT
import ticketing.jooq.booking.tables.references.SCHEDULE
import ticketing.jooq.booking.tables.references.SCHEDULE_SEAT
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import ticketing.jooq.booking.tables.references.BOOKING_ as BOOKING

@Repository
internal class BookingQueryJooqAdapter(private val dsl: DSLContext) : BookingQueryPort {

    override fun findSweepCandidateIds(cutoff: Instant, paymentCutoff: Instant, afterId: Long?, limit: Int): List<Long> =
        dsl.select(BOOKING.ID).from(BOOKING)
            .where(BOOKING.STATUS.eq("PENDING"))
            .and(
                BOOKING.PAYMENT_REQUESTED_AT.isNull.and(BOOKING.EXPIRES_AT.le(cutoff.atOffset(ZoneOffset.UTC)))
                    .or(BOOKING.PAYMENT_REQUESTED_AT.isNotNull.and(BOOKING.EXPIRES_AT.le(paymentCutoff.atOffset(ZoneOffset.UTC)))),
            )
            .and(if (afterId == null) DSL.noCondition() else BOOKING.ID.gt(afterId))
            .orderBy(BOOKING.ID.asc()).limit(limit).fetch { requireNotNull(it[BOOKING.ID]) }

    /** ID 내림차순 커서 페이지. 다음 페이지 존재 여부를 알기 위해 1건 더 읽는다. */
    override fun findMyBookings(memberId: Long, cursor: Long?, size: Int): BookingPage {
        val seatCount = DSL.field(
            DSL.selectCount().from(BOOKING_SEAT).where(BOOKING_SEAT.BOOKING_ID.eq(BOOKING.ID)),
        )

        val rows = dsl.select(
            BOOKING.ID,
            BOOKING.STATUS,
            SCHEDULE.PERFORMANCE_TITLE,
            SCHEDULE.STARTS_AT,
            seatCount,
            BOOKING.TOTAL_AMOUNT,
            BOOKING.CREATED_AT,
        )
            .from(BOOKING)
            .join(SCHEDULE).on(SCHEDULE.SCHEDULE_ID.eq(BOOKING.SCHEDULE_ID))
            .where(BOOKING.MEMBER_ID.eq(memberId))
            .and(if (cursor != null) BOOKING.ID.lt(cursor) else DSL.noCondition())
            .orderBy(BOOKING.ID.desc())
            .limit(size + 1)
            .fetch {
                BookingSummaryView(
                    bookingId = requireNotNull(it[BOOKING.ID]),
                    status = requireNotNull(it[BOOKING.STATUS]),
                    performanceTitle = requireNotNull(it[SCHEDULE.PERFORMANCE_TITLE]),
                    startsAt = requireNotNull(it[SCHEDULE.STARTS_AT]).toInstant(),
                    seatCount = requireNotNull(it[seatCount]),
                    totalAmount = BigDecimal.valueOf(requireNotNull(it[BOOKING.TOTAL_AMOUNT])),
                    createdAt = requireNotNull(it[BOOKING.CREATED_AT]).toInstant(),
                )
            }

        val items = rows.take(size)
        return BookingPage(items, if (rows.size > size) items.last().bookingId else null)
    }

    override fun findDetail(bookingId: Long): BookingDetailView? {
        val head = dsl.select(
            BOOKING.ID, BOOKING.MEMBER_ID, BOOKING.SCHEDULE_ID, BOOKING.STATUS, SCHEDULE.PERFORMANCE_TITLE,
            SCHEDULE.STARTS_AT, BOOKING.TOTAL_AMOUNT, BOOKING.EXPIRES_AT, BOOKING.CREATED_AT,
        )
            .from(BOOKING)
            .join(SCHEDULE).on(SCHEDULE.SCHEDULE_ID.eq(BOOKING.SCHEDULE_ID))
            .where(BOOKING.ID.eq(bookingId))
            .fetchOne() ?: return null

        val seats = dsl.select(BOOKING_SEAT.SEAT_ID, SCHEDULE_SEAT.LABEL, BOOKING_SEAT.PRICE)
            .from(BOOKING_SEAT)
            .join(SCHEDULE_SEAT).on(
                SCHEDULE_SEAT.SCHEDULE_ID.eq(head[BOOKING.SCHEDULE_ID]).and(SCHEDULE_SEAT.SEAT_ID.eq(BOOKING_SEAT.SEAT_ID)),
            )
            .where(BOOKING_SEAT.BOOKING_ID.eq(bookingId))
            .orderBy(BOOKING_SEAT.SEAT_ID.asc())
            .fetch {
                BookingSeatResult(
                    requireNotNull(it[BOOKING_SEAT.SEAT_ID]),
                    requireNotNull(it[SCHEDULE_SEAT.LABEL]),
                    BigDecimal.valueOf(requireNotNull(it[BOOKING_SEAT.PRICE])),
                )
            }

        return BookingDetailView(
            bookingId = requireNotNull(head[BOOKING.ID]),
            memberId = requireNotNull(head[BOOKING.MEMBER_ID]),
            scheduleId = requireNotNull(head[BOOKING.SCHEDULE_ID]),
            status = requireNotNull(head[BOOKING.STATUS]),
            performanceTitle = requireNotNull(head[SCHEDULE.PERFORMANCE_TITLE]),
            startsAt = requireNotNull(head[SCHEDULE.STARTS_AT]).toInstant(),
            totalAmount = BigDecimal.valueOf(requireNotNull(head[BOOKING.TOTAL_AMOUNT])),
            expiresAt = requireNotNull(head[BOOKING.EXPIRES_AT]).toInstant(),
            createdAt = requireNotNull(head[BOOKING.CREATED_AT]).toInstant(),
            seats = seats,
        )
    }
}
