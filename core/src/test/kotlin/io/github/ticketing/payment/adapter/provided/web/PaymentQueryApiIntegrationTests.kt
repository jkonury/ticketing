package io.github.ticketing.payment.adapter.provided.web

import io.github.ticketing.IntegrationTestSupport
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import ticketing.jooq.payment.tables.references.PAYMENT_ as PAYMENT

class PaymentQueryApiIntegrationTests(private val mvc: MockMvc, private val dsl: DSLContext) : IntegrationTestSupport() {
    private val json = JsonMapper.builder().build()

    private fun member(role: String = "USER"): Pair<Long, String> {
        val email = "payment-${UUID.randomUUID()}@example.com"
        val signup = mvc.perform(
            post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123", "name" to "회원", "role" to role))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val login = mvc.perform(
            post("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123"))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return (json.readValue(signup, Map::class.java)["id"] as Number).toLong() to
            (json.readValue(login, Map::class.java)["accessToken"] as String)
    }

    private fun payment(orderId: Long, memberId: Long) {
        dsl.insertInto(PAYMENT)
            .set(PAYMENT.ORDER_ID, orderId).set(PAYMENT.MEMBER_ID, memberId).set(PAYMENT.AMOUNT, 100L)
            .set(PAYMENT.STATUS, "REQUESTED").set(PAYMENT.IDEMPOTENCY_KEY, "query-$orderId")
            .set(PAYMENT.REQUESTED_AT, Instant.parse("2030-01-01T00:00:00Z").atOffset(ZoneOffset.UTC)).execute()
    }

    @Test
    fun `본인 결제만 예매 번호 순으로 반환한다`() {
        val (memberId, token) = member()
        val (otherId, _) = member()
        val first = System.nanoTime()
        payment(first, memberId)
        payment(first + 1, otherId)
        payment(first + 2, memberId)

        mvc.perform(
            get("/api/v1/payments/me").param("bookingIds", "${first + 2},${first + 1},$first,${first + 3}")
                .header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].bookingId").value(first)).andExpect(jsonPath("$[1].bookingId").value(first + 2))
            .andExpect(jsonPath("$[0].status").value("REQUESTED"))
    }

    @Test
    fun `결제 번호 입력과 권한을 검증한다`() {
        val (_, user) = member()
        val (_, organizer) = member("ORGANIZER")
        val ids = (1..51).joinToString(",")

        mvc.perform(get("/api/v1/payments/me").param("bookingIds", ids).header("Authorization", "Bearer $user"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        listOf("", "abc", "1,abc").forEach { invalid ->
            mvc.perform(get("/api/v1/payments/me").param("bookingIds", invalid).header("Authorization", "Bearer $user"))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }
        mvc.perform(get("/api/v1/payments/me").param("bookingIds", "1"))
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        mvc.perform(get("/api/v1/payments/me").param("bookingIds", "1").header("Authorization", "Bearer $organizer"))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
    }
}
