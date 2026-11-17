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
@Table(schema = "coupon", name = "coupon_definition")
internal class CouponDefinitionJpaEntity(
    @Column(name = "name", nullable = false)
    var name: String,
    @Column(name = "description")
    var description: String?,
    @Column(name = "discount_type", nullable = false)
    var discountType: String,
    @Column(name = "discount_value", nullable = false)
    var discountValue: BigDecimal,
    @Column(name = "max_discount")
    var maxDiscount: BigDecimal?,
    @Column(name = "min_order_amount", nullable = false)
    var minOrderAmount: BigDecimal,
    @Column(name = "performance_id")
    var performanceId: Long?,
    @Column(name = "valid_from", nullable = false)
    var validFrom: Instant,
    @Column(name = "valid_until", nullable = false)
    var validUntil: Instant,
    @Column(name = "issue_mode", nullable = false)
    var issueMode: String,
    @Column(name = "total_quantity")
    var totalQuantity: Int?,
    @Column(name = "status", nullable = false)
    var status: String,
    @Column(name = "issue_started_at")
    var issueStartedAt: Instant?,
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    override fun equals(other: Any?): Boolean = other is CouponDefinitionJpaEntity && id != null && id == other.id

    override fun hashCode(): Int = javaClass.hashCode()
}
