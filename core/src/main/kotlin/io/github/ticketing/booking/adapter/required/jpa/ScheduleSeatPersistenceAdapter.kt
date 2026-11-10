package io.github.ticketing.booking.adapter.required.jpa

import io.github.ticketing.booking.application.port.required.ScheduleSeatCommandPort
import io.github.ticketing.booking.domain.ScheduleCopy
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import ticketing.jooq.booking.tables.references.SCHEDULE
import java.time.ZoneOffset

@Repository
internal class ScheduleSeatPersistenceAdapter(
    private val seatRepository: ScheduleSeatCopyJpaRepository,
    private val dsl: DSLContext,
) : ScheduleSeatCommandPort {
    override fun saveIfAbsent(schedule: ScheduleCopy, seats: List<ScheduleSeatCopy>): Boolean {
        // 동시에 같은 이벤트가 와도 한쪽만 삽입되도록 회차 행을 먼저 ON CONFLICT로 선점한다.
        // jOOQ 쓰기는 JPA flush를 일으키지 않으므로 좌석 saveAll보다 앞에 둔다
        val inserted = dsl.insertInto(SCHEDULE)
            .set(SCHEDULE.SCHEDULE_ID, schedule.scheduleId.value)
            .set(SCHEDULE.PERFORMANCE_ID, schedule.performanceId)
            .set(SCHEDULE.PERFORMANCE_TITLE, schedule.performanceTitle)
            .set(SCHEDULE.ORGANIZER_ID, schedule.organizerId)
            .set(SCHEDULE.STARTS_AT, schedule.startsAt.atOffset(ZoneOffset.UTC))
            .set(SCHEDULE.RECEIVED_AT, schedule.receivedAt.atOffset(ZoneOffset.UTC))
            .onConflict(SCHEDULE.SCHEDULE_ID)
            .doNothing()
            .execute()
        if (inserted == 0) return false
        seatRepository.saveAll(seats.map { it.toEntity() })
        return true
    }

    override fun existsSchedule(scheduleId: ScheduleId): Boolean =
        dsl.fetchExists(SCHEDULE, SCHEDULE.SCHEDULE_ID.eq(scheduleId.value))

    override fun findSeats(scheduleId: ScheduleId, seatIds: Collection<SeatId>): List<ScheduleSeatCopy> {
        if (seatIds.isEmpty()) return emptyList()
        return seatRepository.findByKeyScheduleIdAndKeySeatIdIn(scheduleId.value, seatIds.map { it.value })
            .map { it.toDomain() }
    }
}
