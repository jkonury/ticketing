package io.github.ticketing.member.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** secret은 HS256 기준 32바이트 이상이어야 한다. 저장소에 넣지 않고 환경 변수(`JWT_SECRET`)로 받는다. */
@ConfigurationProperties("ticketing.jwt")
data class JwtProperties(
    val secret: String,
    val accessTtl: Duration = Duration.ofMinutes(15),
    val refreshTtl: Duration = Duration.ofDays(14),
) {
    init {
        require(secret.toByteArray().size >= 32) { "ticketing.jwt.secret은 32바이트 이상이어야 합니다." }
    }
}
