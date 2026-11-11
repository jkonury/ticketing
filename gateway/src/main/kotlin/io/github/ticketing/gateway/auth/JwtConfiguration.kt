package io.github.ticketing.gateway.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import java.time.Duration
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("ticketing.jwt")
data class JwtProperties(val secret: String) {
    init {
        require(secret.toByteArray().size >= MIN_SECRET_BYTES) { "ticketing.jwt.secret은 32바이트 이상이어야 합니다." }
    }
}

private const val MIN_SECRET_BYTES = 32

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties::class)
class JwtConfiguration {
    @Bean
    fun jwtDecoder(properties: JwtProperties): ReactiveJwtDecoder =
        NimbusReactiveJwtDecoder.withSecretKey(SecretKeySpec(properties.secret.toByteArray(), "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256).build().apply { setJwtValidator(JwtTimestampValidator(Duration.ZERO)) }
}
