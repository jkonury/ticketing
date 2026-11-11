package io.github.ticketing.member.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** secret은 HS256 기준 32바이트 이상이어야 한다. 저장소에 넣지 않고 환경 변수(`JWT_SECRET`)로 받는다. */
@ConfigurationProperties("ticketing.jwt")
data class JwtProperties(
    val secret: String,
    val accessTtl: Duration = Duration.ofMinutes(DEFAULT_ACCESS_TTL_MINUTES),
    val refreshTtl: Duration = Duration.ofDays(DEFAULT_REFRESH_TTL_DAYS),
) {
    init {
        require(secret.toByteArray().size >= MIN_SECRET_BYTES) { "ticketing.jwt.secret은 32바이트 이상이어야 합니다." }
    }
}

private const val DEFAULT_ACCESS_TTL_MINUTES = 15L
private const val DEFAULT_REFRESH_TTL_DAYS = 14L
private const val MIN_SECRET_BYTES = 32
