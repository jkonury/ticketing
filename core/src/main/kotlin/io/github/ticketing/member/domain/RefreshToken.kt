package io.github.ticketing.member.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(schema = "member", name = "refresh_token")
class RefreshToken(
    @Column(name = "member_id", nullable = false, updatable = false)
    var memberId: Long,
    @Column(name = "token_hash", nullable = false, updatable = false)
    var tokenHash: String,
    @Column(name = "expires_at", nullable = false, updatable = false)
    var expiresAt: Instant,
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null
        protected set

    fun isRevoked(): Boolean = revokedAt != null

    fun isExpired(now: Instant): Boolean = !now.isBefore(expiresAt)

    fun revoke(now: Instant) {
        if (revokedAt == null) revokedAt = now
    }

    override fun equals(other: Any?): Boolean = this === other || (other is RefreshToken && id != null && id == other.id)

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
