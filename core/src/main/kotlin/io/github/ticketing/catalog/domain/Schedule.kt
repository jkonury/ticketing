package io.github.ticketing.catalog.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** 공연의 회차. 다른 애그리거트이므로 공연은 ID로만 참조한다. */
@Entity
@Table(schema = "catalog", name = "schedule")
class Schedule(
    @Column(name = "performance_id", nullable = false, updatable = false)
    var performanceId: Long,
    @Column(name = "starts_at", nullable = false, updatable = false)
    var startsAt: Instant,
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    override fun equals(other: Any?): Boolean = this === other || (other is Schedule && id != null && id == other.id)

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
