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
import io.github.ticketing.member.domain.Member
import io.github.ticketing.member.domain.Role
import io.github.ticketing.member.security.JwtTokenProvider
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.cache.CacheManager
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import ticketing.jooq.catalog.tables.references.PERFORMANCE
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

class AdminPerformanceIntegrationTests(
    private val mvc: MockMvcTester,
    private val registration: CatalogRegistrationService,
    private val tokens: JwtTokenProvider,
    private val caches: CacheManager,
    private val dsl: DSLContext,
) : IntegrationTestSupport() {

    private val json = JsonMapper.builder().build()
    private val organizerId = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
    private val actorId = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
    private val admin = bearer(Role.ADMIN, actorId)
    private val owner = bearer(Role.ORGANIZER, organizerId)

    @Test
    fun `중지하면 공개 조회와 주최자 변경이 차단되고 주최자는 자기 공연을 조회할 수 있다`() {
        val performanceId = performance()
        val scheduleId = requireNotNull(
            registration.registerSchedule(RegisterScheduleCommand(organizerId, performanceId, Instant.parse("2030-01-02T10:00:00Z"))).id,
        )
        val publicPaths = listOf(
            "/api/v1/performances/$performanceId",
            "/api/v1/schedules/$scheduleId",
            "/api/v1/schedules/$scheduleId/seats",
        )
        for (path in publicPaths) assertThat(mvc.get().uri(path)).hasStatus(HttpStatus.OK)
        val performanceCache = requireNotNull(caches.getCache(CatalogCaches.PERFORMANCE))
        val seatsCache = requireNotNull(caches.getCache(CatalogCaches.SCHEDULE_SEATS))
        assertThat(performanceCache.get(performanceId)).isNotNull()
        assertThat(seatsCache.get(scheduleId)).isNotNull()

        assertThat(suspendPerformance(performanceId, "판매 정책 위반")).hasStatus(HttpStatus.OK)
            .bodyJson().extractingPath("$.status").isEqualTo("SUSPENDED")
        assertThat(performanceCache.get(performanceId)).isNull()
        assertThat(seatsCache.get(scheduleId)).isNull()
        for (path in publicPaths) assertThat(mvc.get().uri(path)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(mvc.get().uri("/api/v1/performances").param("size", "100")).hasStatus(HttpStatus.OK)
            .bodyJson().extractingPath("$.items[?(@.id == $performanceId)]").asArray().isEmpty()

        val ownPath = "/api/v1/organizer/performances/$performanceId"
        assertThat(mvc.get().uri(ownPath).header("Authorization", owner)).hasStatus(HttpStatus.OK).bodyJson().apply {
            extractingPath("$.status").isEqualTo("SUSPENDED")
            extractingPath("$.performance.schedules[0].id").convertTo(Long::class.java).isEqualTo(scheduleId)
        }
        assertThat(mvc.get().uri(ownPath).header("Authorization", bearer(Role.ORGANIZER, organizerId + 1)))
            .hasStatus(HttpStatus.NOT_FOUND)
        assertThat(mvc.get().uri(ownPath).header("Authorization", bearer(Role.USER, organizerId)))
            .hasStatus(HttpStatus.FORBIDDEN)
        assertThat(
            mvc.post().uri("/api/v1/performances/$performanceId/schedules")
                .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                .content("""{"startsAt":"2030-01-03T10:00:00Z"}"""),
        ).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code").isEqualTo("PERFORMANCE_SUSPENDED")
        assertThat(
            mvc.post().uri("/api/v1/performances/$performanceId/poster-uploads")
                .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                .content("""{"contentType":"image/jpeg"}"""),
        ).hasStatus(HttpStatus.CONFLICT)

        assertThat(suspendPerformance(performanceId, "다시 중지")).hasStatus(HttpStatus.CONFLICT)
        assertThat(resume(performanceId)).hasStatus(HttpStatus.OK).bodyJson().extractingPath("$.status").isEqualTo("ACTIVE")
        assertThat(resume(performanceId)).hasStatus(HttpStatus.CONFLICT)
        for (path in publicPaths) assertThat(mvc.get().uri(path)).hasStatus(HttpStatus.OK)
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(
                mvc.get().uri("/api/v1/admin/audit-logs").param("action", "PERFORMANCE_SUSPENSION_CHANGED")
                    .param("actorId", "$actorId").header("Authorization", admin),
            ).hasStatus(HttpStatus.OK).bodyJson().apply {
                extractingPath("$.items[*].detail.to").asArray().containsExactlyInAnyOrder("SUSPENDED", "ACTIVE")
                extractingPath("$.items[?(@.detail.to == 'SUSPENDED')].detail.reason").asArray().containsExactly("판매 정책 위반")
            }
        }
    }

    @Test
    fun `공연 취소는 되돌릴 수 없고 공개 조회에서 사라진다`() {
        val performanceId = performance()
        val scheduleId = requireNotNull(
            registration.registerSchedule(RegisterScheduleCommand(organizerId, performanceId, Instant.parse("2030-01-02T10:00:00Z"))).id,
        )
        val publicPaths = listOf(
            "/api/v1/performances/$performanceId",
            "/api/v1/schedules/$scheduleId",
            "/api/v1/schedules/$scheduleId/seats",
        )
        for (path in publicPaths) assertThat(mvc.get().uri(path)).hasStatus(HttpStatus.OK)

        assertThat(cancelPerformance(performanceId, "공연장 사정")).hasStatus(HttpStatus.OK)
            .bodyJson().extractingPath("$.status").isEqualTo("CANCELLED")

        for (path in publicPaths) assertThat(mvc.get().uri(path)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(mvc.get().uri("/api/v1/performances").param("size", "100")).hasStatus(HttpStatus.OK)
            .bodyJson().extractingPath("$.items[?(@.id == $performanceId)]").asArray().isEmpty()
        assertThat(cancelPerformance(performanceId, "재취소")).hasStatus(HttpStatus.CONFLICT)
        assertThat(resume(performanceId)).hasStatus(HttpStatus.CONFLICT)
        assertThat(suspendPerformance(performanceId, "중지")).hasStatus(HttpStatus.CONFLICT)
    }

    @Test
    fun `중지된 공연도 취소할 수 있고 주최자 상세에서만 조회할 수 있다`() {
        val id = performance()
        assertThat(suspendPerformance(id, "판매 점검")).hasStatus(HttpStatus.OK)
        assertThat(cancelPerformance(id, "  공연장 사정  ")).hasStatus(HttpStatus.OK)
            .bodyJson().extractingPath("$.status").isEqualTo("CANCELLED")
        val version = dsl.select(PERFORMANCE.SUSPENSION_VERSION).from(PERFORMANCE).where(PERFORMANCE.ID.eq(id)).fetchSingle().value1()
        assertThat(version).isEqualTo(2L)

        assertThat(mvc.get().uri("$PATH?status=CANCELLED&organizerId=$organizerId").header("Authorization", admin))
            .hasStatus(HttpStatus.OK).bodyJson().apply {
                extractingPath("$.items.length()").isEqualTo(1)
                extractingPath("$.items[0].status").isEqualTo("CANCELLED")
                extractingPath("$.items[0].id").convertTo(Long::class.java).isEqualTo(id)
            }
        assertThat(mvc.get().uri("/api/v1/organizer/performances/$id").header("Authorization", owner))
            .hasStatus(HttpStatus.OK).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED")
        assertThat(
            mvc.post().uri("/api/v1/performances/$id/schedules")
                .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                .content("""{"startsAt":"2030-01-03T10:00:00Z"}"""),
        ).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code").isEqualTo("PERFORMANCE_CANCELLED")
        assertThat(
            mvc.post().uri("/api/v1/performances/$id/poster-uploads")
                .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content("""{"contentType":"image/jpeg"}"""),
        ).hasStatus(HttpStatus.CONFLICT)
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(
                mvc.get().uri("/api/v1/admin/audit-logs").param("action", "PERFORMANCE_CANCELLED")
                    .param("actorId", "$actorId").header("Authorization", admin),
            ).hasStatus(HttpStatus.OK).bodyJson().apply {
                extractingPath("$.items[*].targetId").asArray().containsExactly("$id")
                extractingPath("$.items[*].detail.reason").asArray().containsExactly("공연장 사정")
            }
        }
    }

    @Test
    fun `관리자는 상태와 주최자와 제목으로 거르고 페이지를 이동한다`() {
        val tag = UUID.randomUUID().toString()
        val older = performance("첫 공연 $tag")
        val newer = performance("둘째 공연 $tag")
        val suspended = performance("중지 공연 $tag")
        performance("다른 공연 $tag", organizerId + 1)
        performance()
        assertThat(suspendPerformance(suspended, "점검")).hasStatus(HttpStatus.OK)
        val query = mvc.get().uri(PATH).param("status", "ACTIVE").param("organizerId", "$organizerId")
            .param("title", tag).param("size", "1").header("Authorization", admin)

        assertThat(query).hasStatus(HttpStatus.OK).bodyJson().apply {
            extractingPath("$.items[0].id").convertTo(Long::class.java).isEqualTo(newer)
            extractingPath("$.nextCursor").convertTo(Long::class.java).isEqualTo(newer)
        }
        assertThat(query.param("cursor", "$newer")).hasStatus(HttpStatus.OK).bodyJson().apply {
            extractingPath("$.items[0].id").convertTo(Long::class.java).isEqualTo(older)
            extractingPath("$.nextCursor").isNull()
        }
    }

    @Test
    fun `관리자만 공연을 중지할 수 있고 사유를 검증한다`() {
        val id = performance()
        for (forbidden in listOf(owner, bearer(Role.USER, organizerId))) {
            assertThat(mvc.get().uri(PATH).header("Authorization", forbidden)).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(suspendPerformance(id, "점검", forbidden)).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(resume(id, forbidden)).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(cancelPerformance(id, "점검", forbidden)).hasStatus(HttpStatus.FORBIDDEN)
        }
        for (reason in listOf("", "  ", "가".repeat(201))) {
            assertThat(suspendPerformance(id, reason)).hasStatus(HttpStatus.BAD_REQUEST)
            assertThat(cancelPerformance(id, reason)).hasStatus(HttpStatus.BAD_REQUEST)
        }
        assertThat(
            mvc.put().uri("$PATH/$id/cancellation").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{}"),
        ).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(suspendPerformance(Long.MAX_VALUE, "점검")).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(cancelPerformance(Long.MAX_VALUE, "점검")).hasStatus(HttpStatus.NOT_FOUND)
    }

    private fun suspendPerformance(id: Long, reason: String, token: String = admin) = mvc.put()
        .uri("$PATH/$id/suspension").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(mapOf("reason" to reason)))

    private fun resume(id: Long, token: String = admin) = mvc.delete().uri("$PATH/$id/suspension").header("Authorization", token)

    private fun cancelPerformance(id: Long, reason: String, token: String = admin) = mvc.put()
        .uri("$PATH/$id/cancellation").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(mapOf("reason" to reason)))

    private fun performance(title: String = "공연", ownerId: Long = organizerId): Long {
        val venueId = requireNotNull(
            registration.registerVenue(RegisterVenueCommand("홀", listOf(SeatSpec("A", "1", 1, SeatGrade.VIP)))).id,
        )
        return requireNotNull(
            registration.registerPerformance(
                RegisterPerformanceCommand(ownerId, title, "설명", venueId, listOf(PriceSpec(SeatGrade.VIP, BigDecimal.TEN))),
            ).id,
        )
    }

    private fun bearer(role: Role, id: Long): String {
        val member = Member("test@example.com", "unused", "이름", role, Instant.now()).apply { this.id = id }
        return "Bearer ${tokens.issueAccessToken(member).value}"
    }

    private companion object {
        const val PATH = "/api/v1/admin/performances"
    }
}
