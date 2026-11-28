package io.github.ticketing.catalog.service

import io.github.ticketing.catalog.PerformanceCancelled
import io.github.ticketing.catalog.PerformanceSuspensionChanged
import io.github.ticketing.catalog.config.CatalogCaches
import io.github.ticketing.catalog.domain.Performance
import io.github.ticketing.catalog.domain.PerformanceStatus
import io.github.ticketing.catalog.query.AdminPerformanceQueryRepository
import io.github.ticketing.catalog.repository.PerformanceRepository
import org.springframework.cache.CacheManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class AdminPerformanceService(
    private val performances: PerformanceRepository,
    private val queries: AdminPerformanceQueryRepository,
    private val caches: CacheManager,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {

    @Transactional
    fun suspendPerformance(actorId: Long, id: Long, reason: String): PerformanceStatus =
        changeStatus(actorId, id, PerformanceStatus.SUSPENDED, reason.trim())

    @Transactional
    fun resume(actorId: Long, id: Long): PerformanceStatus = changeStatus(actorId, id, PerformanceStatus.ACTIVE, null)

    @Transactional
    fun cancel(actorId: Long, id: Long, reason: String): PerformanceStatus {
        val performance = transition(id, PerformanceStatus.CANCELLED)
        events.publishEvent(
            PerformanceCancelled(UUID.randomUUID(), actorId, id, performance.suspensionVersion, reason.trim(), clock.instant()),
        )
        return performance.status
    }

    private fun changeStatus(actorId: Long, id: Long, status: PerformanceStatus, reason: String?): PerformanceStatus {
        val performance = transition(id, status)
        events.publishEvent(
            PerformanceSuspensionChanged(
                eventId = UUID.randomUUID(),
                actorId = actorId,
                performanceId = id,
                saleEnabled = status == PerformanceStatus.ACTIVE,
                version = performance.suspensionVersion,
                reason = reason,
                changedAt = clock.instant(),
            ),
        )
        return performance.status
    }

    private fun transition(id: Long, status: PerformanceStatus): Performance {
        val performance = performances.findForUpdate(id) ?: throw PerformanceNotFoundException()
        val error = when (performance.status) {
            PerformanceStatus.CANCELLED -> PerformanceCancelledException()
            status -> PerformanceSuspensionUnchangedException()
            else -> null
        }
        if (error != null) throw error
        performance.changeStatus(status)
        evictPublicViews(id)
        return performance
    }

    private fun evictPublicViews(id: Long) {
        requireNotNull(caches.getCache(CatalogCaches.PERFORMANCE)).evict(id)
        val seats = requireNotNull(caches.getCache(CatalogCaches.SCHEDULE_SEATS))
        queries.scheduleIds(id).forEach(seats::evict)
    }
}
