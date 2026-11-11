package io.github.ticketing.catalog

import io.github.ticketing.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.UUID

@AutoConfigureMockMvc
@RecordApplicationEvents
class CatalogRegisterApiTests(private val mvc: MockMvc) : IntegrationTestSupport() {

    private val json = JsonMapper.builder().build()

    private fun accessToken(role: String): String {
        val email = "$role-${UUID.randomUUID()}@example.com".lowercase()
        mvc.perform(
            post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123", "name" to "홍길동", "role" to role))),
        ).andExpect(status().isCreated)
        val body = mvc.perform(
            post("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123"))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return json.readTree(body)["accessToken"].asString()
    }

    private fun postJson(path: String, token: String?, body: Any): ResultActions = mvc.perform(
        post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)).apply {
            if (token != null) header("Authorization", "Bearer $token")
        },
    )

    private fun created(result: ResultActions): Map<*, *> {
        val response = result.andExpect(status().isCreated).andReturn().response.contentAsString
        return json.readValue(response, Map::class.java)
    }

    private fun seat(section: String, row: String, number: Int, grade: String) =
        mapOf("section" to section, "row" to row, "number" to number, "grade" to grade)

    private val twoGradeSeats = listOf(seat("A", "3", 5, "VIP"), seat("A", "3", 6, "VIP"), seat("B", "1", 1, "R"))

    private val allPrices = listOf(mapOf("grade" to "VIP", "price" to 150000), mapOf("grade" to "R", "price" to 100000))

    private fun registerVenue(token: String, seats: List<Map<String, Any>> = twoGradeSeats): Long =
        (created(postJson("/api/v1/venues", token, mapOf("name" to "올림픽홀", "seats" to seats)))["id"] as Number).toLong()

    private fun registerPerformance(token: String, venueId: Long): Long = (
        created(
            postJson(
                "/api/v1/performances",
                token,
                mapOf("title" to "콘서트", "description" to "설명", "venueId" to venueId, "prices" to allPrices),
            ),
        )["id"] as Number
        ).toLong()

    @Test
    fun `주최자는 공연장을 등록하면 좌석 수를 돌려받는다`() {
        val token = accessToken("ORGANIZER")

        postJson("/api/v1/venues", token, mapOf("name" to "올림픽홀", "seats" to twoGradeSeats))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").isNumber)
            .andExpect(jsonPath("$.seatCount").value(3))
    }

    @Test
    fun `일반 사용자는 403, 비인증 요청은 401을 받는다`() {
        val user = accessToken("USER")
        val body = mapOf("name" to "올림픽홀", "seats" to twoGradeSeats)

        postJson("/api/v1/venues", user, body).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
        // @PreAuthorize는 인자 바인딩 뒤에 평가되므로 본문이 유효해야 403을 확인할 수 있다
        postJson("/api/v1/performances", user, mapOf("title" to "t", "description" to "d", "venueId" to 1, "prices" to allPrices))
            .andExpect(status().isForbidden)
        postJson("/api/v1/performances/1/schedules", user, mapOf("startsAt" to "2027-01-01T10:00:00Z")).andExpect(status().isForbidden)

        postJson("/api/v1/venues", null, body).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
    }

    @Test
    fun `좌석이 없거나 5000개를 넘으면 400이다`() {
        val token = accessToken("ORGANIZER")

        postJson("/api/v1/venues", token, mapOf("name" to "빈 공연장", "seats" to emptyList<Any>()))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        val tooMany = (1..5001).map { seat("A", "1", it, "A") }
        postJson("/api/v1/venues", token, mapOf("name" to "큰 공연장", "seats" to tooMany))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `같은 구역·열·번호의 좌석이 중복되면 400이다`() {
        val token = accessToken("ORGANIZER")

        postJson("/api/v1/venues", token, mapOf("name" to "중복", "seats" to listOf(seat("A", "1", 1, "VIP"), seat("A", "1", 1, "R"))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DUPLICATE_VENUE_SEAT"))
    }

    @Test
    fun `주최자는 공연을 등록한다`() {
        val token = accessToken("ORGANIZER")
        val venueId = registerVenue(token)

        postJson(
            "/api/v1/performances",
            token,
            mapOf("title" to "콘서트", "description" to "설명", "venueId" to venueId, "prices" to allPrices),
        ).andExpect(status().isCreated).andExpect(jsonPath("$.id").isNumber)
    }

    @Test
    fun `공연장 좌석 등급의 가격이 없으면 400이다`() {
        val token = accessToken("ORGANIZER")
        val venueId = registerVenue(token)

        postJson(
            "/api/v1/performances",
            token,
            mapOf(
                "title" to "콘서트",
                "description" to "설명",
                "venueId" to venueId,
                "prices" to listOf(mapOf("grade" to "VIP", "price" to 150000)),
            ),
        ).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PRICE_MISSING_FOR_GRADE"))
    }

    @Test
    fun `없는 공연장으로 공연을 등록하면 404다`() {
        val token = accessToken("ORGANIZER")

        postJson(
            "/api/v1/performances",
            token,
            mapOf("title" to "콘서트", "description" to "설명", "venueId" to Long.MAX_VALUE, "prices" to allPrices),
        ).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("VENUE_NOT_FOUND"))
    }

    @Test
    fun `회차를 등록하면 좌석 수만큼의 좌석과 가격을 담은 이벤트가 발행된다`(events: ApplicationEvents) {
        val token = accessToken("ORGANIZER")
        val performanceId = registerPerformance(token, registerVenue(token))

        val scheduleId = (
            created(
                postJson("/api/v1/performances/$performanceId/schedules", token, mapOf("startsAt" to "2027-01-01T10:00:00Z")),
            )["id"] as Number
            ).toLong()

        val event = events.stream(ScheduleRegistered::class.java).filter { it.scheduleId == scheduleId }.toList().single()
        assertThat(event.performanceId).isEqualTo(performanceId)
        assertThat(event.performanceTitle).isEqualTo("콘서트")
        assertThat(event.startsAt.toString()).isEqualTo("2027-01-01T10:00:00Z")
        assertThat(event.seats).hasSize(3)
        assertThat(event.seats.map { it.seatId }.toSet()).hasSize(3)
        assertThat(event.seats.map { Triple(it.grade, it.label, it.price) }).containsExactlyInAnyOrder(
            Triple("VIP", "A구역 3열 5번", BigDecimal(150000)),
            Triple("VIP", "A구역 3열 6번", BigDecimal(150000)),
            Triple("R", "B구역 1열 1번", BigDecimal(100000)),
        )
    }

    @Test
    fun `다른 주최자의 공연에는 회차를 등록할 수 없고 이벤트도 발행되지 않는다`(events: ApplicationEvents) {
        val owner = accessToken("ORGANIZER")
        val other = accessToken("ORGANIZER")
        val performanceId = registerPerformance(owner, registerVenue(owner))
        val before = events.stream(ScheduleRegistered::class.java).count()

        postJson("/api/v1/performances/$performanceId/schedules", other, mapOf("startsAt" to "2027-01-01T10:00:00Z"))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("NOT_PERFORMANCE_OWNER"))

        assertThat(events.stream(ScheduleRegistered::class.java).count()).isEqualTo(before)
    }

    @Test
    fun `없는 공연에 회차를 등록하면 404다`() {
        val token = accessToken("ORGANIZER")

        postJson("/api/v1/performances/${Long.MAX_VALUE}/schedules", token, mapOf("startsAt" to "2027-01-01T10:00:00Z"))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"))
    }

    @Test
    fun `같은 시작 시각의 회차를 다시 등록하면 409이고 이벤트는 한 번만 발행된다`(events: ApplicationEvents) {
        val token = accessToken("ORGANIZER")
        val performanceId = registerPerformance(token, registerVenue(token))
        val body = mapOf("startsAt" to "2027-02-01T10:00:00Z")

        postJson("/api/v1/performances/$performanceId/schedules", token, body).andExpect(status().isCreated)

        postJson("/api/v1/performances/$performanceId/schedules", token, body)
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DUPLICATE_SCHEDULE"))

        assertThat(events.stream(ScheduleRegistered::class.java).filter { it.performanceId == performanceId }.count()).isEqualTo(1)
    }
}
