package io.github.ticketing.settlement.adapter.provided.web

import io.github.ticketing.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import ticketing.jooq.settlement.tables.references.DAILY_SETTLEMENT
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class SettlementApiIntegrationTests(private val mockMvcTester: MockMvcTester, private val dsl: DSLContext) : IntegrationTestSupport() {
    private val json = JsonMapper.builder().build()

    private fun member(role: String): Pair<Long, String> {
        val email = "${UUID.randomUUID()}@example.com"
        val signup = json.writeValueAsString(mapOf("email" to email, "password" to "password123", "name" to "홍길동", "role" to role))
        val registered = mockMvcTester.post().uri("/api/v1/members").contentType(MediaType.APPLICATION_JSON).content(signup).exchange()
        assertThat(registered).hasStatus(HttpStatus.CREATED)
        val login = json.writeValueAsString(mapOf("email" to email, "password" to "password123"))
        val token = mockMvcTester.post().uri("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON).content(login).exchange()
        assertThat(token).hasStatus(HttpStatus.OK)
        return (json.readValue(registered.response.contentAsString, Map::class.java)["id"] as Number).toLong() to
            (json.readValue(token.response.contentAsString, Map::class.java)["accessToken"] as String)
    }

    private fun insert(organizer: Long, performance: Long, date: String, platformDiscount: Long = 0) {
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
            .set(DAILY_SETTLEMENT.PLATFORM_DISCOUNT_AMOUNT, BigDecimal(platformDiscount))
            .set(DAILY_SETTLEMENT.CALCULATED_AT, OffsetDateTime.parse("2026-11-12T00:00:00Z"))
            .execute()
    }

    private fun get(url: String, token: String): MvcTestResult =
        mockMvcTester.get().uri(url).header("Authorization", "Bearer $token").exchange()

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

        assertThat(get("/api/v1/settlements?from=2026-11-01&to=2026-11-10", token))
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.length()").isEqualTo(3)
                extractingPath("$[0].performanceId").isEqualTo(base + 3)
                extractingPath("$[1].performanceId").isEqualTo(base + 1)
                extractingPath("$[2].performanceId").isEqualTo(base + 2)
                extractingPath("$[0].settlementDate").isEqualTo("2026-11-01")
                extractingPath("$[0].saleCount").isEqualTo(3)
                extractingPath("$[0].saleAmount").isEqualTo(300000)
                extractingPath("$[0].refundCount").isEqualTo(1)
                extractingPath("$[0].refundAmount").isEqualTo(100000)
                extractingPath("$[0].netAmount").isEqualTo(200000)
                extractingPath("$[0].feeRate").isEqualTo(0.1)
                extractingPath("$[0].feeAmount").isEqualTo(20000)
                extractingPath("$[0].payoutAmount").isEqualTo(180000)
            }
        assertThat(get("/api/v1/settlements?from=2026-12-01&to=2026-12-31", token))
            .hasStatus(HttpStatus.OK)
            .bodyJson().extractingPath("$.length()").isEqualTo(0)
    }

    @Test
    fun `플랫폼 할인 부담액은 주최자 응답에 노출하지 않는다`() {
        val (owner, token) = member("ORGANIZER")
        insert(owner, System.nanoTime(), "2026-11-05", platformDiscount = 2000)

        assertThat(get("/api/v1/settlements?from=2026-11-01&to=2026-11-10", token))
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$[0].saleAmount").isEqualTo(300000)
                doesNotHavePath("$[0].platformDiscountAmount")
                doesNotHavePath("$[0].platformDiscount")
                doesNotHavePath("$[0].discountAmount")
            }
    }

    @Test
    fun `일반 사용자는 403이고 인증이 없으면 401이다`() {
        val (_, token) = member("USER")
        val url = "/api/v1/settlements?from=2026-11-01&to=2026-11-10"

        assertThat(get(url, token)).hasStatus(HttpStatus.FORBIDDEN)
        mockMvcTester.get().uri(url).assertThat().hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `잘못된 기간과 날짜 또는 누락된 날짜는 400이다`() {
        val (_, token) = member("ORGANIZER")
        val queries = listOf("from=2026-11-10&to=2026-11-01", "from=2026-11-01&to=2026-12-02", "from=bad&to=2026-11-10", "to=2026-11-10")

        queries.forEach { query ->
            assertThat(get("/api/v1/settlements?$query", token))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_SETTLEMENT_PERIOD")
        }
    }
}
