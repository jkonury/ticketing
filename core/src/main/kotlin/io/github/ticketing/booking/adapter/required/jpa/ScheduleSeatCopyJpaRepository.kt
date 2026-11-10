package io.github.ticketing.booking.adapter.required.jpa

import org.springframework.data.jpa.repository.JpaRepository

internal interface ScheduleSeatCopyJpaRepository : JpaRepository<ScheduleSeatCopyJpaEntity, ScheduleSeatKey> {
    fun findByKeyScheduleIdAndKeySeatIdIn(scheduleId: Long, seatIds: Collection<Long>): List<ScheduleSeatCopyJpaEntity>
}
