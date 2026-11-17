package io.github.ticketing.member.repository

import io.github.ticketing.member.domain.Member
import io.github.ticketing.member.domain.Role
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface MemberRepository : JpaRepository<Member, Long> {
    fun findByEmail(email: String): Member?

    fun existsByEmail(email: String): Boolean

    // 마지막 관리자 검사를 직렬화하려고 ADMIN 행 전체를 잠근다. 관리자 수는 적다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByRole(role: Role): List<Member>
}
