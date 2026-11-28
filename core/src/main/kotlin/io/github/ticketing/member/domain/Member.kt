package io.github.ticketing.member.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(schema = "member", name = "member")
class Member(
    @Column(nullable = false, updatable = false)
    var email: String,
    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,
    @Column(nullable = false)
    var name: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var role: Role,
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: MemberStatus = MemberStatus.ACTIVE
        protected set

    fun changeStatus(newStatus: MemberStatus) {
        status = newStatus
    }

    fun changeRole(newRole: Role) {
        role = newRole
    }

    override fun equals(other: Any?): Boolean = this === other || (other is Member && id != null && id == other.id)

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
