package io.github.ticketing.booking.adapter.required.jpa

import org.springframework.data.repository.Repository

internal interface ScheduleSeatCopyJpaRepository : Repository<ScheduleSeatCopyJpaEntity, ScheduleSeatKey> {
    fun <S : ScheduleSeatCopyJpaEntity> saveAll(entities: Iterable<S>): List<S>

    fun findByKeyScheduleIdAndKeySeatIdIn(scheduleId: Long, seatIds: Collection<Long>): List<ScheduleSeatCopyJpaEntity>
}
