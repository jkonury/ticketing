package io.github.ticketing.payment.adapter.required.jpa

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
@Table(schema = "payment", name = "payment")
class PaymentJpaEntity(
    @Column(name = "order_id", nullable = false, updatable = false)
    val orderId: Long,
    @Column(name = "member_id", nullable = false, updatable = false)
    val memberId: Long,
    @Column(name = "amount", nullable = false, updatable = false)
    val amount: BigDecimal,
    @Column(name = "status", nullable = false)
    var status: String,
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    val idempotencyKey: String,
    @Column(name = "pg_transaction_id")
    var pgTransactionId: String?,
    @Column(name = "failure_reason")
    var failureReason: String?,
    @Column(name = "requested_at", nullable = false, updatable = false)
    val requestedAt: Instant,
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
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
