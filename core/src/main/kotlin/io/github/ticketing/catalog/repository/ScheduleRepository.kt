package io.github.ticketing.catalog.repository

import io.github.ticketing.catalog.domain.Schedule
import org.springframework.data.jpa.repository.JpaRepository

interface ScheduleRepository : JpaRepository<Schedule, Long>
