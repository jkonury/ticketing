package io.github.ticketing.payment.adapter.provided.web

import com.nimbusds.jose.jwk.source.ImmutableSecret
import io.github.ticketing.TEST_JWT_SECRET
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit.MINUTES
import javax.crypto.spec.SecretKeySpec

object PaymentQueryFixture {
    private val encoder = NimbusJwtEncoder(ImmutableSecret(SecretKeySpec(TEST_JWT_SECRET.toByteArray(), "HmacSHA256")))

    fun token(memberId: Long, role: String = "USER"): String {
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .subject(memberId.toString())
            .issuedAt(now)
            .expiresAt(now.plus(5, MINUTES))
            .claim("role", role)
            .build()
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).tokenValue
    }
}
