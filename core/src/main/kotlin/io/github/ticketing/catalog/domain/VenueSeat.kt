package io.github.ticketing.catalog.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/** 공연장 좌석. [Venue] 애그리거트의 일부라 Venue를 통해서만 만든다. */
@Entity
@Table(schema = "catalog", name = "venue_seat")
class VenueSeat(
    @Column(nullable = false, updatable = false)
    val section: String,
    @Column(name = "row_label", nullable = false, updatable = false)
    val rowLabel: String,
    @Column(name = "seat_number", nullable = false, updatable = false)
    val seatNumber: Int,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val grade: SeatGrade,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    /** 같은 공연장 안에서 좌석을 식별하는 자연키. */
    val key: SeatKey get() = SeatKey(section, rowLabel, seatNumber)

    override fun equals(other: Any?): Boolean = this === other || (other is VenueSeat && id != null && id == other.id)

    override fun hashCode(): Int = id?.hashCode() ?: 0
}

data class SeatKey(
    val section: String,
    val rowLabel: String,
    val seatNumber: Int,
)
