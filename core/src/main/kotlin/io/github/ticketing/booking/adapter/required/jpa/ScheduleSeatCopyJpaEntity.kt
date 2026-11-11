package io.github.ticketing.booking.adapter.required.jpa

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable
import java.io.Serializable
import java.math.BigDecimal

@Embeddable
data class ScheduleSeatKey(
    @Column(name = "schedule_id")
    var scheduleId: Long = 0,
    @Column(name = "seat_id")
    var seatId: Long = 0,
) : Serializable {
    private companion object {
        private const val serialVersionUID = 1L
    }
}

@Entity
@Table(schema = "booking", name = "schedule_seat")
class ScheduleSeatCopyJpaEntity(
    @EmbeddedId
    var key: ScheduleSeatKey,
    @Column(name = "grade", nullable = false)
    var grade: String,
    @Column(name = "label", nullable = false)
    var label: String,
    @Column(name = "price", nullable = false)
    var price: BigDecimal,
) : Persistable<ScheduleSeatKey> {
    // 식별자를 직접 할당하므로 신규 여부를 알리지 않으면 save가 merge(사전 SELECT)로 간다
    @Transient
    private var newEntity: Boolean = true

    override fun getId(): ScheduleSeatKey = key

    override fun isNew(): Boolean = newEntity

    @PostPersist
    @PostLoad
    fun markNotNew() {
        newEntity = false
    }
}
