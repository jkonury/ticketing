package io.github.ticketing.member.security

import io.github.ticketing.member.domain.Member
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class JwtTokenProvider(
    private val encoder: JwtEncoder,
    private val properties: JwtProperties,
    private val clock: Clock,
) {

    fun issueAccessToken(member: Member): AccessToken {
        val now = clock.instant()
        val claims = JwtClaimsSet.builder()
            .subject(checkNotNull(member.id).toString())
            .claim(ROLE_CLAIM, member.role.name)
            .issuedAt(now)
            .expiresAt(now.plus(properties.accessTtl))
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val value = encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return AccessToken(value, properties.accessTtl.seconds)
    }

    data class AccessToken(val value: String, val expiresInSeconds: Long)

    companion object {
        const val ROLE_CLAIM = "role"
    }
}
