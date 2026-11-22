package io.github.ticketing.payment.adapter.provided.web

import com.nimbusds.jose.jwk.source.ImmutableSecret
import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.TEST_JWT_SECRET
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit.MINUTES
import javax.crypto.spec.SecretKeySpec
import ticketing.jooq.payment.tables.references.PAYMENT_ as PAYMENT

class PaymentQueryApiIntegrationTests(private val mockMvcTester: MockMvcTester, private val dsl: DSLContext) : IntegrationTestSupport() {
    private val encoder = NimbusJwtEncoder(ImmutableSecret(SecretKeySpec(TEST_JWT_SECRET.toByteArray(), "HmacSHA256")))

    private fun token(memberId: Long, role: String = "USER"): String {
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .subject(memberId.toString())
            .issuedAt(now)
            .expiresAt(now.plus(5, MINUTES))
            .claim("role", role)
            .build()
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).tokenValue
    }

    private fun payment(orderId: Long, memberId: Long) {
        dsl.insertInto(PAYMENT)
            .set(PAYMENT.ORDER_ID, orderId).set(PAYMENT.MEMBER_ID, memberId).set(PAYMENT.AMOUNT, 100L)
            .set(PAYMENT.STATUS, "REQUESTED").set(PAYMENT.IDEMPOTENCY_KEY, "query-$orderId")
            .set(PAYMENT.REQUESTED_AT, Instant.parse("2030-01-01T00:00:00Z").atOffset(ZoneOffset.UTC)).execute()
    }

    @Test
    fun `본인 결제만 예매 번호 순으로 반환한다`() {
        val memberId = 100L
        val otherId = 101L
        val token = token(memberId)
        val first = System.nanoTime()
        payment(first, memberId)
        payment(first + 1, otherId)
        payment(first + 2, memberId)

        mockMvcTester.get()
            .uri("/api/v1/payments/me")
            .param("bookingIds", "${first + 2},${first + 1},$first,${first + 3}")
            .header("Authorization", "Bearer $token")
            .assertThat()
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.length()").isEqualTo(2)
                extractingPath("$[0].bookingId").isEqualTo(first)
                extractingPath("$[1].bookingId").isEqualTo(first + 2)
                extractingPath("$[0].status").isEqualTo("REQUESTED")
            }
    }

    @Test
    fun `결제 번호 입력과 권한을 검증한다`() {
        val user = token(100)
        val organizer = token(100, "ORGANIZER")
        val ids = (1..51).joinToString(",")

        mockMvcTester.get().uri("/api/v1/payments/me").param("bookingIds", ids).header("Authorization", "Bearer $user")
            .assertThat()
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST")
        listOf("", "abc", "1,abc").forEach { invalid ->
            mockMvcTester.get().uri("/api/v1/payments/me").param("bookingIds", invalid).header("Authorization", "Bearer $user")
                .assertThat()
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST")
        }
        mockMvcTester.get().uri("/api/v1/payments/me").param("bookingIds", "1")
            .assertThat()
            .hasStatus(HttpStatus.UNAUTHORIZED)
            .bodyJson().extractingPath("$.code").isEqualTo("UNAUTHORIZED")
        mockMvcTester.get().uri("/api/v1/payments/me").param("bookingIds", "1").header("Authorization", "Bearer $organizer")
            .assertThat()
            .hasStatus(HttpStatus.FORBIDDEN)
            .bodyJson().extractingPath("$.code").isEqualTo("FORBIDDEN")
    }
}
