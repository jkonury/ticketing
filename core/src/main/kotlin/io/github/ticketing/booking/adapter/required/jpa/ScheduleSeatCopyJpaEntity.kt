package io.github.ticketing.booking.adapter.required.jpa

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.io.Serializable
import java.math.BigDecimal

@Embeddable
data class ScheduleSeatKey(
    @Column(name = "schedule_id") val scheduleId: Long = 0,
    @Column(name = "seat_id") val seatId: Long = 0,
) : Serializable

@Entity
@Table(schema = "booking", name = "schedule_seat")
class ScheduleSeatCopyJpaEntity(
    @EmbeddedId
    val key: ScheduleSeatKey,
    @Column(name = "grade", nullable = false)
    val grade: String,
    @Column(name = "label", nullable = false)
    val label: String,
    @Column(name = "price", nullable = false)
    val price: BigDecimal,
)
