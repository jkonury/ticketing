package io.github.ticketing

import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit.MINUTES
import javax.crypto.spec.SecretKeySpec

internal const val SETTLEMENT_TEST_JWT_SECRET = "test-secret-test-secret-test-secret-0123"

@SpringBootTest(properties = ["ticketing.jwt.secret=$SETTLEMENT_TEST_JWT_SECRET", "springdoc.api-docs.enabled=true"])
@AutoConfigureMockMvc
@Import(SettlementTestContainers::class)
abstract class SettlementWebTestSupport {
    protected fun token(memberId: Long, role: String = "ORGANIZER"): String {
        val encoder = NimbusJwtEncoder(ImmutableSecret(SecretKeySpec(SETTLEMENT_TEST_JWT_SECRET.toByteArray(), "HmacSHA256")))
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
