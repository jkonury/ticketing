package io.github.ticketing.settlement.adapter.provided.web

import io.github.ticketing.IntegrationTestSupport
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import ticketing.jooq.settlement.tables.references.DAILY_SETTLEMENT
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class SettlementApiIntegrationTests(private val mvc: MockMvc, private val dsl: DSLContext) : IntegrationTestSupport() {
    private val json = JsonMapper.builder().build()

    private fun member(role: String): Pair<Long, String> {
        val email = "${UUID.randomUUID()}@example.com"
        val signup = json.writeValueAsString(mapOf("email" to email, "password" to "password123", "name" to "홍길동", "role" to role))
        val registered = mvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON).content(signup))
            .andExpect(status().isCreated).andReturn().response.contentAsString
        val login = json.writeValueAsString(mapOf("email" to email, "password" to "password123"))
        val token = mvc.perform(post("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON).content(login))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return (json.readValue(registered, Map::class.java)["id"] as Number).toLong() to
            (json.readValue(token, Map::class.java)["accessToken"] as String)
    }

    private fun insert(organizer: Long, performance: Long, date: String) {
        dsl.insertInto(DAILY_SETTLEMENT)
            .set(DAILY_SETTLEMENT.SETTLEMENT_DATE, LocalDate.parse(date))
            .set(DAILY_SETTLEMENT.PERFORMANCE_ID, performance)
            .set(DAILY_SETTLEMENT.ORGANIZER_ID, organizer)
            .set(DAILY_SETTLEMENT.SALE_COUNT, 3)
            .set(DAILY_SETTLEMENT.SALE_AMOUNT, BigDecimal(300000))
            .set(DAILY_SETTLEMENT.REFUND_COUNT, 1)
            .set(DAILY_SETTLEMENT.REFUND_AMOUNT, BigDecimal(100000))
            .set(DAILY_SETTLEMENT.NET_AMOUNT, BigDecimal(200000))
            .set(DAILY_SETTLEMENT.FEE_RATE, BigDecimal("0.1000"))
            .set(DAILY_SETTLEMENT.FEE_AMOUNT, BigDecimal(20000))
            .set(DAILY_SETTLEMENT.PAYOUT_AMOUNT, BigDecimal(180000))
            .set(DAILY_SETTLEMENT.CALCULATED_AT, OffsetDateTime.parse("2026-11-12T00:00:00Z"))
            .execute()
    }

    @Test
    fun `주최자는 기간 내 자기 정산만 날짜와 공연 순으로 조회한다`() {
        val (owner, token) = member("ORGANIZER")
        val (other, _) = member("ORGANIZER")
        val base = System.nanoTime()
        insert(owner, base + 2, "2026-11-10")
        insert(owner, base + 1, "2026-11-10")
        insert(owner, base + 3, "2026-11-01")
        insert(owner, base + 4, "2026-10-31")
        insert(other, base + 5, "2026-11-01")

        mvc.perform(get("/api/v1/settlements?from=2026-11-01&to=2026-11-10").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].performanceId").value(base + 3))
            .andExpect(jsonPath("$[1].performanceId").value(base + 1))
            .andExpect(jsonPath("$[2].performanceId").value(base + 2))
            .andExpect(jsonPath("$[0].settlementDate").value("2026-11-01"))
            .andExpect(jsonPath("$[0].saleCount").value(3)).andExpect(jsonPath("$[0].saleAmount").value(300000))
            .andExpect(jsonPath("$[0].refundCount").value(1)).andExpect(jsonPath("$[0].refundAmount").value(100000))
            .andExpect(jsonPath("$[0].netAmount").value(200000)).andExpect(jsonPath("$[0].feeRate").value(0.1))
            .andExpect(jsonPath("$[0].feeAmount").value(20000)).andExpect(jsonPath("$[0].payoutAmount").value(180000))
        mvc.perform(get("/api/v1/settlements?from=2026-12-01&to=2026-12-31").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `일반 사용자는 403이고 인증이 없으면 401이다`() {
        val (_, token) = member("USER")
        val url = "/api/v1/settlements?from=2026-11-01&to=2026-11-10"

        mvc.perform(get(url).header("Authorization", "Bearer $token")).andExpect(status().isForbidden)
        mvc.perform(get(url)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `잘못된 기간과 날짜 또는 누락된 날짜는 400이다`() {
        val (_, token) = member("ORGANIZER")
        val queries = listOf("from=2026-11-10&to=2026-11-01", "from=2026-11-01&to=2026-12-02", "from=bad&to=2026-11-10", "to=2026-11-10")

        queries.forEach { query ->
            mvc.perform(get("/api/v1/settlements?$query").header("Authorization", "Bearer $token"))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_SETTLEMENT_PERIOD"))
        }
    }
}
