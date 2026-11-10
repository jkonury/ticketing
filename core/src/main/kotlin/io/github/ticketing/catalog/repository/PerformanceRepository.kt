package io.github.ticketing.catalog.repository

import io.github.ticketing.catalog.domain.Performance
import org.springframework.data.jpa.repository.JpaRepository

interface PerformanceRepository : JpaRepository<Performance, Long>
