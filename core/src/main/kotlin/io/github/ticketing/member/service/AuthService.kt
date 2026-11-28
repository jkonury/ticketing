package io.github.ticketing.member.service

import io.github.ticketing.member.domain.Member
import io.github.ticketing.member.domain.MemberStatus
import io.github.ticketing.member.domain.RefreshToken
import io.github.ticketing.member.repository.MemberRepository
import io.github.ticketing.member.repository.RefreshTokenRepository
import io.github.ticketing.member.security.JwtProperties
import io.github.ticketing.member.security.JwtTokenProvider
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.HexFormat

@Service
class AuthService(
    private val members: MemberRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenProvider: JwtTokenProvider,
    private val properties: JwtProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    // 어떤 비밀번호와도 일치하지 않는, 실제 인코더가 만든 해시
    private val dummyHash: String by lazy {
        checkNotNull(passwordEncoder.encode(Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))))
    }

    @Transactional
    fun login(email: String, password: String): IssuedTokens {
        val credentials = members.findCredentialsByEmail(email.trim().lowercase())
        // 없는 이메일도 같은 비용으로 해시 비교를 해 응답 시간으로 존재 여부가 드러나지 않게 한다
        val hash = credentials?.passwordHash ?: dummyHash
        val matches = passwordEncoder.matches(password, hash)
        if (credentials == null || !matches) throw InvalidCredentialsException()
        val member = members.findForUpdate(credentials.id) ?: throw InvalidCredentialsException()
        ensureActive(member)
        return issue(member)
    }

    // 재사용된(이미 폐기된) 토큰이 오면 탈취로 보고 그 회원의 모든 리프레시 토큰을 폐기한다.
    // 폐기 결과가 예외로 롤백되지 않도록 noRollbackFor를 지정한다
    @Transactional(noRollbackFor = [InvalidRefreshTokenException::class])
    fun refresh(rawRefreshToken: String): IssuedTokens {
        val now = clock.instant()
        val tokenHash = hash(rawRefreshToken)
        val member = lockRefreshMember(tokenHash)
        val stored = refreshTokens.findByTokenHash(tokenHash) ?: throw InvalidRefreshTokenException()
        if (!stored.isExpired(now)) ensureActive(member)
        if (stored.isRevoked()) rejectReuse(stored, now)
        if (stored.isExpired(now)) throw InvalidRefreshTokenException()
        stored.revoke(now)
        return issue(member)
    }

    @Transactional
    fun revoke(rawRefreshToken: String) {
        refreshTokens.findByTokenHash(hash(rawRefreshToken))?.revoke(clock.instant())
    }

    // 정지와 토큰 발급이 엇갈리지 않도록 회원 행을 토큰 행보다 먼저 잠근다
    private fun lockRefreshMember(tokenHash: String): Member {
        val memberId = refreshTokens.findMemberIdByTokenHash(tokenHash) ?: throw InvalidRefreshTokenException()
        return members.findForUpdate(memberId) ?: throw InvalidRefreshTokenException()
    }

    private fun ensureActive(member: Member) {
        if (member.status == MemberStatus.SUSPENDED) throw MemberSuspendedException()
    }

    private fun rejectReuse(stored: RefreshToken, now: Instant): Nothing {
        log.warn("폐기된 리프레시 토큰이 재사용되었습니다. memberId={}", stored.memberId)
        refreshTokens.revokeAllByMemberId(stored.memberId, now)
        throw InvalidRefreshTokenException()
    }

    private fun issue(member: Member): IssuedTokens {
        val now = clock.instant()
        val raw = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        refreshTokens.save(RefreshToken(checkNotNull(member.id), hash(raw), now.plus(properties.refreshTtl), now))
        val access = tokenProvider.issueAccessToken(member)
        return IssuedTokens(access.value, access.expiresInSeconds, raw, properties.refreshTtl.seconds)
    }

    private fun hash(raw: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()))

    data class IssuedTokens(
        val accessToken: String,
        val accessExpiresInSeconds: Long,
        val refreshToken: String,
        val refreshExpiresInSeconds: Long,
    )
}
