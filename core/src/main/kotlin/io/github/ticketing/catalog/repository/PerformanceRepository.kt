package io.github.ticketing.catalog.repository

import io.github.ticketing.catalog.domain.Performance
import org.springframework.data.repository.Repository
import java.util.Optional

interface PerformanceRepository : Repository<Performance, Long> {
    fun findById(id: Long): Optional<Performance>

    fun <S : Performance> save(entity: S): S
}
