package io.github.ticketing.member.web

import io.github.ticketing.member.domain.Member
import io.github.ticketing.member.domain.Role
import io.github.ticketing.member.service.AuthService

data class MemberResponse(val id: Long, val email: String, val name: String, val role: Role) {
    companion object {
        fun from(member: Member) = MemberResponse(checkNotNull(member.id), member.email, member.name, member.role)
    }
}

data class TokenResponse(
    val accessToken: String,
    val accessExpiresIn: Long,
    val refreshToken: String,
    val refreshExpiresIn: Long,
) {
    companion object {
        fun from(tokens: AuthService.IssuedTokens) = TokenResponse(
            tokens.accessToken,
            tokens.accessExpiresInSeconds,
            tokens.refreshToken,
            tokens.refreshExpiresInSeconds,
        )
    }
}
