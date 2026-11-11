package io.github.ticketing.catalog.domain

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapKeyColumn
import jakarta.persistence.MapKeyEnumerated
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(schema = "catalog", name = "performance")
class Performance(
    @Column(name = "organizer_id", nullable = false, updatable = false)
    var organizerId: Long,
    @Column(nullable = false)
    var title: String,
    @Column(nullable = false)
    var description: String,
    @Column(name = "venue_id", nullable = false, updatable = false)
    var venueId: Long,
    prices: Map<SeatGrade, BigDecimal>,
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,
) {
    init {
        require(title.isNotBlank()) { "공연 제목은 비어 있을 수 없다" }
        require(prices.isNotEmpty()) { "가격표는 비어 있을 수 없다" }
        require(prices.values.all { it.signum() >= 0 }) { "가격은 0 이상이어야 한다" }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        schema = "catalog",
        name = "performance_price",
        joinColumns = [JoinColumn(name = "performance_id")],
    )
    @MapKeyColumn(name = "grade")
    @MapKeyEnumerated(EnumType.STRING)
    @Column(name = "price", nullable = false)
    private var _prices: MutableMap<SeatGrade, BigDecimal> = prices.toMutableMap()

    val prices: Map<SeatGrade, BigDecimal> get() = _prices

    override fun equals(other: Any?): Boolean = this === other || (other is Performance && id != null && id == other.id)

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
