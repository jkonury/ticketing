package io.github.ticketing.booking.adapter.required.jpa

import io.github.ticketing.booking.adapter.required.redis.ScheduleSeatCache
import io.github.ticketing.booking.application.port.required.ScheduleSeatCommandPort
import io.github.ticketing.booking.domain.ScheduleCopy
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import ticketing.jooq.booking.tables.references.PERFORMANCE_SALE
import ticketing.jooq.booking.tables.references.SCHEDULE
import ticketing.jooq.booking.tables.references.SCHEDULE_SEAT
import java.time.ZoneOffset

@Repository
internal class ScheduleSeatPersistenceAdapter(
    private val seatRepository: ScheduleSeatCopyJpaRepository,
    private val dsl: DSLContext,
    private val cache: ScheduleSeatCache,
) : ScheduleSeatCommandPort {
    override fun saveIfAbsent(schedule: ScheduleCopy, seats: List<ScheduleSeatCopy>): Boolean {
        // 동시에 같은 이벤트가 와도 한쪽만 삽입되도록 회차 행을 먼저 ON CONFLICT로 선점한다.
        // jOOQ 쓰기는 JPA flush를 일으키지 않으므로 좌석 saveAll보다 앞에 둔다
        val saleEnabled = lockSaleStatus(schedule.performanceId)
        val inserted = dsl.insertInto(SCHEDULE)
            .set(SCHEDULE.SCHEDULE_ID, schedule.scheduleId.value)
            .set(SCHEDULE.PERFORMANCE_ID, schedule.performanceId)
            .set(SCHEDULE.PERFORMANCE_TITLE, schedule.performanceTitle)
            .set(SCHEDULE.ORGANIZER_ID, schedule.organizerId)
            .set(SCHEDULE.STARTS_AT, schedule.startsAt.atOffset(ZoneOffset.UTC))
            .set(SCHEDULE.RECEIVED_AT, schedule.receivedAt.atOffset(ZoneOffset.UTC))
            .set(SCHEDULE.SALE_ENABLED, saleEnabled)
            .onConflict(SCHEDULE.SCHEDULE_ID)
            .doNothing()
            .execute()
        if (inserted == 0) return false
        seatRepository.saveAll(seats.map { it.toEntity() })
        cache.putAfterCommit(schedule.scheduleId, seats.map { it.copy(startsAt = schedule.startsAt) })
        return true
    }

    override fun findSchedule(scheduleId: ScheduleId): ScheduleCopy? =
        dsl.selectFrom(SCHEDULE).where(SCHEDULE.SCHEDULE_ID.eq(scheduleId.value)).fetchOne()?.let {
            ScheduleCopy(
                scheduleId,
                requireNotNull(it.performanceId),
                requireNotNull(it.performanceTitle),
                requireNotNull(it.organizerId),
                requireNotNull(it.startsAt).toInstant(),
                requireNotNull(it.receivedAt).toInstant(),
            )
        }

    override fun isSaleEnabled(scheduleId: ScheduleId): Boolean = dsl.select(SCHEDULE.SALE_ENABLED).from(SCHEDULE)
        .where(SCHEDULE.SCHEDULE_ID.eq(scheduleId.value)).fetchOne(SCHEDULE.SALE_ENABLED) == true

    override fun updateSaleStatus(performanceId: Long, saleEnabled: Boolean, version: Long) {
        val changed = dsl.insertInto(PERFORMANCE_SALE)
            .set(PERFORMANCE_SALE.PERFORMANCE_ID, performanceId)
            .set(PERFORMANCE_SALE.SALE_ENABLED, saleEnabled)
            .set(PERFORMANCE_SALE.STATUS_VERSION, version)
            .onConflict(PERFORMANCE_SALE.PERFORMANCE_ID)
            .doUpdate()
            .set(PERFORMANCE_SALE.SALE_ENABLED, saleEnabled)
            .set(PERFORMANCE_SALE.STATUS_VERSION, version)
            .where(PERFORMANCE_SALE.STATUS_VERSION.lt(version))
            .execute()
        if (changed > 0) {
            dsl.update(SCHEDULE)
                .set(SCHEDULE.SALE_ENABLED, saleEnabled)
                .where(SCHEDULE.PERFORMANCE_ID.eq(performanceId))
                .execute()
        }
    }

    // 상태 변경이 회차 등록보다 먼저 도착하거나 둘이 겹쳐도 같은 상태 행을 기준으로 반영한다
    private fun lockSaleStatus(performanceId: Long): Boolean {
        dsl.insertInto(PERFORMANCE_SALE)
            .set(PERFORMANCE_SALE.PERFORMANCE_ID, performanceId)
            .onConflict(PERFORMANCE_SALE.PERFORMANCE_ID)
            .doNothing()
            .execute()
        return requireNotNull(
            dsl.select(PERFORMANCE_SALE.SALE_ENABLED).from(PERFORMANCE_SALE)
                .where(PERFORMANCE_SALE.PERFORMANCE_ID.eq(performanceId))
                .forUpdate()
                .fetchOne(PERFORMANCE_SALE.SALE_ENABLED),
        )
    }

    override fun existsSchedule(scheduleId: ScheduleId): Boolean = dsl.fetchExists(SCHEDULE, SCHEDULE.SCHEDULE_ID.eq(scheduleId.value))

    override fun findSeats(scheduleId: ScheduleId, seatIds: Collection<SeatId>): List<ScheduleSeatCopy> {
        if (seatIds.isEmpty()) return emptyList()
        val requested = seatIds.distinct()
        val cached = cache.find(scheduleId, requested).filter { it.startsAt != null }
        val cachedIds = cached.map { it.seatId }.toSet()
        val missing = requested.filterNot { it in cachedIds }
        val loaded = if (missing.isEmpty()) emptyList() else loadSeats(scheduleId, missing)
        cache.putAfterCommit(scheduleId, loaded)
        return cached + loaded
    }

    private fun loadSeats(scheduleId: ScheduleId, seatIds: Collection<SeatId>): List<ScheduleSeatCopy> = dsl.select()
        .from(SCHEDULE_SEAT)
        .join(SCHEDULE).on(SCHEDULE.SCHEDULE_ID.eq(SCHEDULE_SEAT.SCHEDULE_ID))
        .where(SCHEDULE_SEAT.SCHEDULE_ID.eq(scheduleId.value))
        .and(SCHEDULE_SEAT.SEAT_ID.`in`(seatIds.map { it.value }))
        .fetch {
            ScheduleSeatCopy(
                scheduleId,
                SeatId(requireNotNull(it.get(SCHEDULE_SEAT.SEAT_ID))),
                requireNotNull(it.get(SCHEDULE_SEAT.GRADE)),
                requireNotNull(it.get(SCHEDULE_SEAT.LABEL)),
                requireNotNull(it.get(SCHEDULE_SEAT.PRICE)).toBigDecimal(),
                requireNotNull(it.get(SCHEDULE.STARTS_AT)).toInstant(),
            )
        }
}
