package io.github.ticketing.catalog.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(schema = "catalog", name = "venue")
class Venue(
    @Column(nullable = false)
    var name: String,
    seats: List<VenueSeat>,
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,
) {
    init {
        require(name.isNotBlank()) { "공연장 이름은 비어 있을 수 없다" }
        require(seats.isNotEmpty()) { "공연장에는 좌석이 1개 이상 있어야 한다" }
        val keys = seats.map { it.key }
        require(keys.size == keys.toSet().size) { "좌석 키(구역, 열, 번호)는 중복될 수 없다" }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @OneToMany(fetch = FetchType.LAZY, cascade = [CascadeType.PERSIST], orphanRemoval = true)
    @JoinColumn(name = "venue_id", nullable = false, updatable = false)
    private var _seats: MutableList<VenueSeat> = seats.toMutableList()

    val seats: List<VenueSeat> get() = _seats

    override fun equals(other: Any?): Boolean = this === other || (other is Venue && id != null && id == other.id)

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
