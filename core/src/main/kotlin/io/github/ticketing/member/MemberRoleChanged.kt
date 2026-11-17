package io.github.ticketing.member

import java.time.Instant
import java.util.UUID

/**
 * 회원 역할이 바뀌었다. actorId가 null이면 시스템 행위(관리자 부트스트랩)다.
 * from, to는 다른 모듈이 member.domain을 참조하지 않도록 Role 이름 문자열이다.
 * eventId는 소비자가 멱등 키로 쓴다.
 */
data class MemberRoleChanged(
    val eventId: UUID,
    val actorId: Long?,
    val memberId: Long,
    val from: String,
    val to: String,
    val changedAt: Instant,
)
