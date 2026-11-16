package io.github.ticketing.gateway.warmup

import com.nimbusds.jwt.SignedJWT
import io.github.ticketing.gateway.admission.admissionSchedule
import io.github.ticketing.gateway.auth.JwtConfiguration
import io.github.ticketing.gateway.auth.JwtProperties
import io.github.ticketing.gateway.auth.bearerToken
import io.github.ticketing.gateway.route.GatewayProperties
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.mock.env.MockEnvironment
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

internal class GatewayWarmupTests {
    private val properties = JwtProperties("warmup-test-secret-01234567890123456789")
    private val now = Instant.parse("2026-11-16T00:00:00Z")

    @Test
    fun `예열 토큰은 짧은 만료와 서버 전용 역할을 갖는다`() {
        val token = warmupToken(properties.secret, Clock.fixed(now, ZoneOffset.UTC))

        val claims = SignedJWT.parse(token).jwtClaimsSet

        assertThat(claims.subject).isEqualTo("-1")
        assertThat(claims.getStringClaim("role")).isEqualTo("STARTUP_WARMUP")
        assertThat(claims.expirationTime.toInstant()).isEqualTo(now.plusSeconds(120))
    }

    @Test
    fun `예열은 운영과 같은 Bearer 및 본문 파서를 사용한다`() {
        val mapper = JsonMapper.builder().build()

        assertThat(bearerToken("bEaReR token ")).isEqualTo("token")
        assertThat(bearerToken("Bearer")).isEmpty()
        assertThat(bearerToken("Basic token")).isNull()
        assertThat(admissionSchedule(mapper, """{"scheduleId":7}""".toByteArray())).isEqualTo(7)
        assertThat(admissionSchedule(mapper, """{"scheduleId":"7"}""".toByteArray())).isNull()
    }

    @Test
    fun `실제 JWT 디코더가 예열 서명을 검증한다`() = runTest {
        val token = warmupToken(properties.secret, Clock.systemUTC())

        val decoded = JwtConfiguration().jwtDecoder(properties).decode(token).awaitSingle()

        assertThat(decoded.subject).isEqualTo("-1")
    }

    @Test
    fun `core를 기다리는 시간도 예열 상한에 포함한다`() {
        val runner = GatewayWarmupRunner(
            GatewayWarmupProperties(maxDuration = Duration.ofMillis(100), localIterations = 0),
            properties,
            GatewayProperties(coreUri = "http://127.0.0.1:1"),
            JwtConfiguration().jwtDecoder(properties),
            JsonMapper.builder().build(),
            MockEnvironment(),
        )
        val started = System.nanoTime()

        runner.run(DefaultApplicationArguments())

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3))
    }
}
