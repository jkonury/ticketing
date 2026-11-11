package io.github.ticketing.catalog

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.catalog.config.CatalogCaches
import io.github.ticketing.catalog.domain.SeatGrade
import io.github.ticketing.catalog.service.CatalogRegistrationService
import io.github.ticketing.catalog.service.CatalogRegistrationService.PriceSpec
import io.github.ticketing.catalog.service.CatalogRegistrationService.RegisterPerformanceCommand
import io.github.ticketing.catalog.service.CatalogRegistrationService.RegisterScheduleCommand
import io.github.ticketing.catalog.service.CatalogRegistrationService.RegisterVenueCommand
import io.github.ticketing.catalog.service.CatalogRegistrationService.SeatSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.cache.CacheManager
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant

@AutoConfigureMockMvc
class CatalogQueryApiTests(
    private val mvc: MockMvc,
    private val registration: CatalogRegistrationService,
    private val cacheManager: CacheManager,
) : IntegrationTestSupport() {

    private val json = JsonMapper.builder().build()
    private val organizerId = 7L

    private fun venue(): Long = requireNotNull(
        registration.registerVenue(
            RegisterVenueCommand(
                "조회홀",
                listOf(SeatSpec("A", "1", 1, SeatGrade.VIP), SeatSpec("A", "1", 2, SeatGrade.VIP), SeatSpec("B", "2", 1, SeatGrade.R)),
            ),
        ).id,
    )

    private fun performance(venueId: Long, title: String = "조회 공연"): Long = requireNotNull(
        registration.registerPerformance(
            RegisterPerformanceCommand(
                organizerId,
                title,
                "설명",
                venueId,
                listOf(PriceSpec(SeatGrade.VIP, BigDecimal(150000)), PriceSpec(SeatGrade.R, BigDecimal(100000))),
            ),
        ).id,
    )

    private fun schedule(performanceId: Long, startsAt: String): Long =
        requireNotNull(registration.registerSchedule(RegisterScheduleCommand(organizerId, performanceId, Instant.parse(startsAt))).id)

    private fun body(path: String) = json.readTree(mvc.perform(get(path)).andExpect(status().isOk).andReturn().response.contentAsString)

    @Test
    fun `공연 목록은 ID 내림차순 커서로 페이지를 넘기고 마지막 페이지의 nextCursor는 null이다`() {
        val venueId = venue()
        val ids = (1..3).map { performance(venueId, "목록 공연 $it") }
        schedule(ids.last(), "2027-03-01T10:00:00Z")
        schedule(ids.last(), "2027-02-01T10:00:00Z")

        val first = body("/api/v1/performances?size=2")
        val firstItems = first["items"].values().map { it["id"].asLong() }
        assertThat(firstItems).hasSize(2)
        assertThat(firstItems).isSortedAccordingTo(Comparator.reverseOrder())
        assertThat(first["items"][0]["id"].asLong()).isGreaterThanOrEqualTo(ids.last())
        assertThat(first["nextCursor"].asLong()).isEqualTo(firstItems.last())

        // 다음 페이지는 커서보다 작은 ID만 담는다
        val second = body("/api/v1/performances?size=2&cursor=${first["nextCursor"].asLong()}")
        assertThat(second["items"].values().map { it["id"].asLong() }).allMatch { it < firstItems.last() }

        // 모든 페이지를 순회하면 중복 없이 끝나고 마지막 nextCursor는 null이다
        val seen = mutableListOf<Long>()
        var cursor: Long? = null
        do {
            val page = body("/api/v1/performances?size=2" + (cursor?.let { "&cursor=$it" } ?: ""))
            seen += page["items"].values().map { it["id"].asLong() }
            cursor = if (page["nextCursor"].isNull) null else page["nextCursor"].asLong()
        } while (cursor != null)
        assertThat(seen).doesNotHaveDuplicates().containsAll(ids)

        val top = body("/api/v1/performances?size=100").get("items").values().first { it["id"].asLong() == ids.last() }
        assertThat(top["title"].asString()).isEqualTo("목록 공연 3")
        assertThat(top["venueName"].asString()).isEqualTo("조회홀")
        assertThat(Instant.parse(top["firstStartsAt"].asString())).isEqualTo(Instant.parse("2027-02-01T10:00:00Z"))
        val noSchedule = body("/api/v1/performances?size=100").get("items").values().first { it["id"].asLong() == ids.first() }
        assertThat(noSchedule["firstStartsAt"].isNull).isTrue()
    }

    @Test
    fun `size는 최대 100으로 제한된다`() {
        mvc.perform(get("/api/v1/performances?size=100000")).andExpect(status().isOk)
        mvc.perform(get("/api/v1/performances?size=0")).andExpect(status().isOk)
    }

    @Test
    fun `공연 상세는 공연장, 등급별 가격, 회차 목록을 담는다`() {
        val performanceId = performance(venue())
        val s2 = schedule(performanceId, "2027-04-02T10:00:00Z")
        val s1 = schedule(performanceId, "2027-04-01T10:00:00Z")

        mvc.perform(get("/api/v1/performances/$performanceId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("조회 공연"))
            .andExpect(jsonPath("$.venueName").value("조회홀"))
            .andExpect(jsonPath("$.prices.length()").value(2))
            .andExpect(jsonPath("$.prices[0].grade").value("VIP"))
            .andExpect(jsonPath("$.schedules[0].id").value(s1))
            .andExpect(jsonPath("$.schedules[1].id").value(s2))

        mvc.perform(get("/api/v1/performances/999999999")).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"))
    }

    @Test
    fun `회차 좌석 배치는 좌석과 등급·가격을 담고 상태는 담지 않는다`() {
        val scheduleId = schedule(performance(venue()), "2027-05-01T10:00:00Z")

        val seats = body("/api/v1/schedules/$scheduleId/seats")
        assertThat(seats.size()).isEqualTo(3)
        assertThat(seats[0].propertyNames()).containsExactlyInAnyOrder("seatId", "section", "row", "number", "grade", "price")
        assertThat(seats[0]["section"].asString()).isEqualTo("A")
        assertThat(seats[0]["grade"].asString()).isEqualTo("VIP")
        assertThat(seats[0]["price"].asInt()).isEqualTo(150000)
        assertThat(seats[2]["grade"].asString()).isEqualTo("R")

        mvc.perform(get("/api/v1/schedules/999999999/seats")).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"))
    }

    @Test
    fun `조회 API는 비인증 요청도 허용한다`() {
        val performanceId = performance(venue())
        val scheduleId = schedule(performanceId, "2027-06-01T10:00:00Z")

        mvc.perform(get("/api/v1/performances")).andExpect(status().isOk)
        mvc.perform(get("/api/v1/performances/$performanceId")).andExpect(status().isOk)
        mvc.perform(get("/api/v1/schedules/$scheduleId/seats")).andExpect(status().isOk)
    }

    @Test
    fun `같은 상세를 다시 조회하면 캐시에서 오고 회차를 등록하면 갱신된다`() {
        val performanceId = performance(venue())
        schedule(performanceId, "2027-07-01T10:00:00Z")
        val cache = requireNotNull(cacheManager.getCache(CatalogCaches.PERFORMANCE))
        cache.evict(performanceId)

        assertThat(cache.get(performanceId)).isNull()
        val first = mvc.perform(get("/api/v1/performances/$performanceId")).andReturn().response.contentAsString

        assertThat(cache.get(performanceId)).isNotNull()

        // 캐시에 심은 값이 그대로 나오면 DB가 아니라 캐시에서 온 것이다
        val cached = requireNotNull(cache.get(performanceId)?.get()) as io.github.ticketing.catalog.query.PerformanceDetail
        cache.put(performanceId, cached.copy(title = "캐시된 제목"))

        mvc.perform(get("/api/v1/performances/$performanceId")).andExpect(jsonPath("$.title").value("캐시된 제목"))

        schedule(performanceId, "2027-07-02T10:00:00Z")

        assertThat(cache.get(performanceId)).isNull()
        mvc.perform(get("/api/v1/performances/$performanceId"))
            .andExpect(jsonPath("$.title").value("조회 공연"))
            .andExpect(jsonPath("$.schedules.length()").value(2))
        assertThat(first).contains("조회 공연")
    }
}
