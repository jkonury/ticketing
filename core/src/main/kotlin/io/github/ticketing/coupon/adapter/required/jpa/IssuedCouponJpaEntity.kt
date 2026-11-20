package io.github.ticketing.coupon.adapter.required.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(schema = "coupon", name = "issued_coupon")
internal class IssuedCouponJpaEntity(
    @Column(name = "definition_id", nullable = false, updatable = false)
    var definitionId: Long,
    @Column(name = "member_id", nullable = false, updatable = false)
    var memberId: Long,
    @Column(name = "status", nullable = false)
    var status: String,
    @Column(name = "order_id")
    var orderId: Long?,
    @Column(name = "discount_amount")
    var discountAmount: BigDecimal?,
    @Column(name = "reserved_until")
    var reservedUntil: Instant?,
    @Column(name = "issued_at", nullable = false, updatable = false)
    var issuedAt: Instant,
    @Column(name = "used_at")
    var usedAt: Instant?,
    @Column(name = "reservation_attempt_id", nullable = false)
    var reservationAttemptId: Long = 0,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    override fun equals(other: Any?): Boolean = other is IssuedCouponJpaEntity && id != null && id == other.id

    override fun hashCode(): Int = javaClass.hashCode()
}
