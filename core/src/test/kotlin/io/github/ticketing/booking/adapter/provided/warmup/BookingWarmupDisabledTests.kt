package io.github.ticketing.booking.adapter.provided.warmup

import io.github.ticketing.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class BookingWarmupDisabledTests(private val mvc: MockMvc) : IntegrationTestSupport() {
    @Test
    fun `기본 설정에서는 내부 예열 경로가 없다`() {
        mvc.perform(post("/internal/startup-warmup").with(jwt().authorities(SimpleGrantedAuthority("ROLE_STARTUP_WARMUP"))))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `일반 예매 API는 예열용 음수 회차를 받지 않는다`() {
        mvc.perform(
            post("/api/v1/bookings").with(jwt().authorities(SimpleGrantedAuthority("ROLE_USER")))
                .header("Idempotency-Key", "warmup-negative").contentType(MediaType.APPLICATION_JSON)
                .content("""{"scheduleId":-1,"seatIds":[1]}"""),
        ).andExpect(status().isBadRequest)
    }
}
