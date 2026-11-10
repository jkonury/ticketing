package io.github.ticketing.catalog.repository

import io.github.ticketing.catalog.domain.Schedule
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface ScheduleRepository : JpaRepository<Schedule, Long> {
    fun existsByPerformanceIdAndStartsAt(performanceId: Long, startsAt: Instant): Boolean
}
