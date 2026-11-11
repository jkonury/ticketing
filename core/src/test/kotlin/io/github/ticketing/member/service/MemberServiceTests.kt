package io.github.ticketing.member.service

import io.github.ticketing.member.domain.Member
import io.github.ticketing.member.domain.RefreshToken
import io.github.ticketing.member.domain.Role
import io.github.ticketing.member.repository.MemberRepository
import io.github.ticketing.member.repository.RefreshTokenRepository
import io.github.ticketing.member.security.JwtProperties
import io.github.ticketing.member.security.JwtTokenProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class MemberServiceTests {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val members = FakeMembers()
    private val tokens = FakeRefreshTokens()
    private val encoder = TestPasswordEncoder()
    private val properties = JwtProperties("test-secret-012345678901234567890", refreshTtl = Duration.ofDays(1))
    private val jwtEncoder = JwtEncoder { parameters ->
        Jwt.withTokenValue("access-token").header("alg", "HS256").claims { it.putAll(parameters.claims.claims) }.build()
    }
    private val memberService = MemberService(members, encoder, clock)
    private val authService = AuthService(members, tokens, encoder, JwtTokenProvider(jwtEncoder, properties, clock), properties, clock)

    @Test
    fun `가입은 이메일과 이름을 정규화하고 저장된 회원을 조회한다`() {
        val member = signUp()

        assertThat(member.email).isEqualTo("test@example.com")
        assertThat(member.name).isEqualTo("이름")
        assertThat(member.passwordHash).isEqualTo("encoded:password")
        assertThat(member.createdAt).isEqualTo(now)
        assertThat(memberService.get(requireNotNull(member.id))).isSameAs(member)
        assertThatThrownBy { memberService.get(999) }.isInstanceOf(MemberNotFoundException::class.java)
    }

    @Test
    fun `이메일 중복과 저장 중 경합은 같은 예외로 변환한다`() {
        signUp()

        assertThatThrownBy { signUp() }.isInstanceOf(EmailAlreadyExistsException::class.java)
        members.failSave = true
        assertThatThrownBy { signUp("other@example.com") }
            .isInstanceOf(EmailAlreadyExistsException::class.java)
            .hasCauseInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `로그인은 이메일을 정규화하고 잘못된 인증은 동일하게 거절한다`() {
        signUp()

        val issued = authService.login(" TEST@example.com ", "password")

        assertThat(issued.accessToken).isEqualTo("access-token")
        assertThat(issued.refreshExpiresInSeconds).isEqualTo(Duration.ofDays(1).seconds)
        assertThatThrownBy { authService.login("test@example.com", "wrong") }.isInstanceOf(InvalidCredentialsException::class.java)
        assertThatThrownBy { authService.login("absent@example.com", "wrong") }.isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    fun `리프레시 토큰을 회전하고 이전 토큰 재사용시 회원의 모든 토큰을 폐기한다`() {
        signUp()
        val first = authService.login("test@example.com", "password")
        val second = authService.refresh(first.refreshToken)

        assertThat(second.refreshToken).isNotEqualTo(first.refreshToken)
        assertThat(tokens.values.count { !it.isRevoked() }).isEqualTo(1)
        assertThatThrownBy { authService.refresh(first.refreshToken) }.isInstanceOf(InvalidRefreshTokenException::class.java)
        assertThat(tokens.values).allMatch { it.isRevoked() }
        assertThatThrownBy { authService.refresh(second.refreshToken) }.isInstanceOf(InvalidRefreshTokenException::class.java)
    }

    @Test
    fun `만료되거나 존재하지 않는 리프레시 토큰은 거절한다`() {
        signUp()
        val issued = authService.login("test@example.com", "password")
        tokens.values.single().expiresAt = now

        assertThatThrownBy { authService.refresh(issued.refreshToken) }.isInstanceOf(InvalidRefreshTokenException::class.java)
        assertThatThrownBy { authService.refresh("unknown") }.isInstanceOf(InvalidRefreshTokenException::class.java)
    }

    private fun signUp(email: String = " TEST@example.com ") = memberService.signUp(
        MemberService.SignUpCommand(email, "password", " 이름 ", Role.USER),
    )
}

private class TestPasswordEncoder : PasswordEncoder {
    override fun encode(rawPassword: CharSequence?): String = "encoded:${requireNotNull(rawPassword)}"

    override fun matches(rawPassword: CharSequence?, encodedPassword: String?): Boolean =
        rawPassword != null && encodedPassword != null && encode(rawPassword) == encodedPassword
}

private class FakeMembers : MemberRepository {
    private val values = mutableListOf<Member>()
    var failSave = false

    override fun findById(id: Long): Optional<Member> = Optional.ofNullable(values.find { it.id == id })

    override fun <S : Member> saveAndFlush(entity: S): S {
        if (failSave) throw DataIntegrityViolationException("duplicate")
        entity.id = values.size.toLong() + 1
        values.add(entity)
        return entity
    }

    override fun findByEmail(email: String): Member? = values.find { it.email == email }

    override fun existsByEmail(email: String): Boolean = findByEmail(email) != null
}

private class FakeRefreshTokens : RefreshTokenRepository {
    val values = mutableListOf<RefreshToken>()

    override fun <S : RefreshToken> save(entity: S): S {
        values.add(entity)
        return entity
    }

    override fun findByTokenHash(tokenHash: String): RefreshToken? = values.find { it.tokenHash == tokenHash }

    override fun revokeAllByMemberId(memberId: Long, now: Instant): Int {
        val active = values.filter { it.memberId == memberId && !it.isRevoked() }
        active.forEach { it.revoke(now) }
        return active.size
    }
}
