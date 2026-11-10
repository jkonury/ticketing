package io.github.ticketing.member.repository

import io.github.ticketing.member.domain.RefreshToken
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {

    // 같은 리프레시 토큰으로 동시에 갱신해도 한 번만 성공하도록 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByTokenHash(tokenHash: String): RefreshToken?

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.memberId = :memberId and t.revokedAt is null")
    fun revokeAllByMemberId(memberId: Long, now: Instant): Int
}
