package io.github.ticketing.booking.adapter.required.jpa

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.MapsId
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(schema = "booking", name = "booking")
class BookingJpaEntity(
    @Column(name = "member_id", nullable = false, updatable = false)
    var memberId: Long,
    @Column(name = "schedule_id", nullable = false, updatable = false)
    var scheduleId: Long,
    @Column(name = "status", nullable = false)
    var status: String,
    @Column(name = "total_amount", nullable = false, updatable = false)
    var totalAmount: BigDecimal,
    @Column(name = "hold_token", nullable = false, updatable = false)
    var holdToken: String,
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    var idempotencyKey: String,
    @Column(name = "expires_at", nullable = false, updatable = false)
    var expiresAt: Instant,
    @Column(name = "payment_requested_at")
    var paymentRequestedAt: Instant?,
    @Column(name = "confirmed_at")
    var confirmedAt: Instant?,
    @Column(name = "cancelled_at")
    var cancelledAt: Instant?,
    @Column(name = "cancel_reason")
    var cancelReason: String?,
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    @OneToMany(mappedBy = "booking", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var seats: MutableList<BookingSeatJpaEntity> = mutableListOf()
}

@Embeddable
data class BookingSeatKey(
    @Column(name = "booking_id")
    var bookingId: Long = 0,
    @Column(name = "seat_id")
    var seatId: Long = 0,
) : Serializable

@Entity
@Table(schema = "booking", name = "booking_seat")
class BookingSeatJpaEntity(
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("bookingId")
    @JoinColumn(name = "booking_id")
    var booking: BookingJpaEntity,
    seatId: Long,
    @Column(name = "price", nullable = false)
    var price: BigDecimal,
) {
    @EmbeddedId
    var key: BookingSeatKey = BookingSeatKey(booking.id ?: 0, seatId)
}
