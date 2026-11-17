package io.github.ticketing.member.domain

/**
 * USER, ORGANIZER는 가입 때 고를 수 있다. ADMIN은 가입으로 얻을 수 없고
 * 부트스트랩 설정 또는 관리자의 역할 변경으로만 부여한다 (ADR 0034).
 */
enum class Role {
    USER,
    ORGANIZER,
    ADMIN,
}
