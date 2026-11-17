package io.github.ticketing.member.service

import io.github.ticketing.member.MemberRoleChanged
import io.github.ticketing.member.domain.Member
import io.github.ticketing.member.domain.Role
import io.github.ticketing.member.repository.MemberRepository
import io.github.ticketing.member.repository.RefreshTokenRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class AdminMemberService(
    private val members: MemberRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {

    /** 역할을 바꾸면 그 회원의 리프레시 토큰을 모두 폐기해 새 역할이 다음 갱신부터 적용되게 한다. actorId null은 시스템 행위다. */
    @Transactional
    fun changeRole(actorId: Long?, memberId: Long, newRole: Role): Member {
        // 동시에 서로를 강등해도 둘 다 통과하지 않도록 ADMIN 행을 잠근 뒤 센다
        val admins = members.findAllByRole(Role.ADMIN)
        val member = members.findById(memberId).orElseThrow { MemberNotFoundException() }
        val oldRole = member.role
        if (oldRole == newRole) return member
        if (oldRole == Role.ADMIN && admins.size <= 1) throw LastAdminException()

        val now = clock.instant()
        member.changeRole(newRole)
        refreshTokens.revokeAllByMemberId(memberId, now)
        events.publishEvent(MemberRoleChanged(UUID.randomUUID(), actorId, memberId, oldRole.name, newRole.name, now))
        return member
    }
}
