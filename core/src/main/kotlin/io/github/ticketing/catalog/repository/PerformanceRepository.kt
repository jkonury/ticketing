package io.github.ticketing.catalog.repository

import io.github.ticketing.catalog.domain.Performance
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface PerformanceRepository : JpaRepository<Performance, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select performance from Performance performance where performance.id = :id")
    fun findForPosterUpdate(id: Long): Performance?
}
