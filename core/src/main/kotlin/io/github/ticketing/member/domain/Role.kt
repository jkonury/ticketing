package io.github.ticketing.member.domain

/** 일반 사용자와 주최자 두 가지만 둔다. 플랫폼 관리자는 두지 않는다 (ADR 0013). */
enum class Role {
    USER,
    ORGANIZER,
}
