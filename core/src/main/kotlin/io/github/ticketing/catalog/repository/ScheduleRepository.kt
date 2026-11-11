package io.github.ticketing.catalog.repository

import io.github.ticketing.catalog.domain.Schedule
import org.springframework.data.repository.Repository
import java.time.Instant

interface ScheduleRepository : Repository<Schedule, Long> {
    fun <S : Schedule> saveAndFlush(entity: S): S

    fun existsByPerformanceIdAndStartsAt(performanceId: Long, startsAt: Instant): Boolean
}
