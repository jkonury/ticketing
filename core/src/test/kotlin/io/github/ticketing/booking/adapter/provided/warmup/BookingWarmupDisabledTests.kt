package io.github.ticketing.booking.adapter.provided.warmup

import io.github.ticketing.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.assertj.MockMvcTester

class BookingWarmupDisabledTests(private val mockMvcTest: MockMvcTester) : IntegrationTestSupport() {
    @Test
    fun `기본 설정에서는 예열 자격 발급 경로가 없다`() {
        mockMvcTest.post()
            .uri("/internal/startup-warmup/credential")
            .with(jwt().authorities(SimpleGrantedAuthority("ROLE_STARTUP_WARMUP")))
            .assertThat()
            .hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `기본 설정에서는 예열 자격 헤더가 있어도 일반 예매 체인에서 인증 없이 거절된다`() {
        mockMvcTest.post()
            .uri("/api/v1/bookings")
            .header("Idempotency-Key", WARMUP_WINNER_KEY)
            .contentType(MediaType.APPLICATION_JSON).content("""{"scheduleId":1,"seatIds":[1]}""")
            .assertThat()
            .hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `일반 예매 API는 예열용 음수 회차를 받지 않는다`() {
        mockMvcTest.post()
            .uri("/api/v1/bookings")
            .with(jwt().authorities(SimpleGrantedAuthority("ROLE_USER")))
            .header("Idempotency-Key", "warmup-negative")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"scheduleId":-1,"seatIds":[1]}""")
            .assertThat()
            .hasStatus(HttpStatus.BAD_REQUEST)
    }
}
