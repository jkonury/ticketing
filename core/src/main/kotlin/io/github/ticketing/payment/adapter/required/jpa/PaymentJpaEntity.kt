package io.github.ticketing.payment.adapter.required.jpa

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(schema = "payment", name = "payment")
class PaymentJpaEntity(
    @Column(name = "order_id", nullable = false, updatable = false)
    val orderId: Long,
    @Column(name = "member_id", nullable = false, updatable = false)
    val memberId: Long,
    @Column(nullable = false, updatable = false, precision = 12, scale = 0)
    val amount: BigDecimal,
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    val idempotencyKey: String,
    @Column(name = "requested_at", nullable = false, updatable = false)
    val requestedAt: Instant,

    @Column(nullable = false)
    var status: String,
    @Column(name = "pg_transaction_id")
    var pgTransactionId: String?,
    @Column(name = "failure_reason", columnDefinition = "text")
    var failureReason: String?,
    @Column(name = "approved_at")
    var approvedAt: Instant?,
    @Column(name = "failed_at")
    var failedAt: Instant?,
    @Column(name = "refund_requested_at")
    var refundRequestedAt: Instant?,
    @Column(name = "refunded_at")
    var refundedAt: Instant?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Version
    @Column(nullable = false)
    var version: Long = 0

    override fun equals(other: Any?): Boolean = this === other || (other is PaymentJpaEntity && id != null && id == other.id)

    override fun hashCode(): Int = javaClass.hashCode()
}
