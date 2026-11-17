package io.github.ticketing.audit

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.audit.service.AdminActionRecorder
import io.github.ticketing.member.MemberRoleChanged
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class AuditLogIntegrationTests(
    private val mvc: MockMvc,
    private val dsl: DSLContext,
    private val recorder: AdminActionRecorder,
    private val publisher: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
) : IntegrationTestSupport() {

    private val json = JsonMapper.builder().build()

    private fun count(eventId: UUID) = dsl.fetchCount(dsl.selectFrom("audit.admin_action").where("source_event_id = ?", eventId))

    private fun token(role: String): String {
        val email = "audit-${UUID.randomUUID()}@example.com"
        mvc.perform(
            post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123", "name" to "홍길동"))),
        ).andExpect(status().isCreated)
        dsl.execute("UPDATE member.member SET role = ? WHERE email = ?", role, email)
        val body = mvc.perform(
            post("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123"))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return json.readValue(body, Map::class.java)["accessToken"].toString()
    }

    private fun record(action: String, actorId: Long?, at: Instant) =
        recorder.record(UUID.randomUUID(), actorId, action, "TEST", "1", mapOf("k" to "v"), at)

    @Test
    fun `역할 변경 이벤트는 감사 로그 1건으로 저장된다`() {
        val eventId = UUID.randomUUID()
        tx.executeWithoutResult {
            publisher.publishEvent(MemberRoleChanged(eventId, 7L, 3L, "USER", "ADMIN", Instant.now()))
        }

        await().atMost(10, TimeUnit.SECONDS).untilAsserted { assertThat(count(eventId)).isEqualTo(1) }
        val row = dsl.fetchOne(
            "SELECT actor_id, action, target_type, target_id, detail::text AS d FROM audit.admin_action WHERE source_event_id = ?",
            eventId,
        )!!
        assertThat(row["actor_id"]).isEqualTo(7L)
        assertThat(row["action"]).isEqualTo("MEMBER_ROLE_CHANGED")
        assertThat(row["target_type"]).isEqualTo("MEMBER")
        assertThat(row["target_id"]).isEqualTo("3")
        assertThat(json.readValue(row["d"].toString(), Map::class.java)).isEqualTo(mapOf("from" to "USER", "to" to "ADMIN"))
    }

    @Test
    fun `같은 이벤트를 두 번 처리해도 1건만 저장된다`() {
        val eventId = UUID.randomUUID()
        repeat(2) { recorder.record(eventId, null, "X", "T", "1", emptyMap(), Instant.now()) }

        assertThat(count(eventId)).isEqualTo(1)
    }

    @Test
    fun `비관리자는 감사 로그를 조회할 수 없다`() {
        mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", "Bearer ${token("USER")}"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `필터와 keyset 페이지네이션으로 조회한다`() {
        val admin = token("ADMIN")
        val action = "FILTER_${UUID.randomUUID()}"
        val base = Instant.parse("2030-01-01T00:00:00Z")
        repeat(3) { record(action, 99L, base.plusSeconds(it * 10L)) }
        record("OTHER", 99L, base)

        val first = mvc.perform(get("/api/v1/admin/audit-logs?action=$action&size=2").header("Authorization", "Bearer $admin"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].detail.k").value("v"))
            .andExpect(jsonPath("$.items[0].actorId").value(99))
            .andReturn().response.contentAsString
        val cursor = json.readValue(first, Map::class.java)["nextCursor"]
        assertThat(cursor).isNotNull

        mvc.perform(get("/api/v1/admin/audit-logs?action=$action&size=2&cursor=$cursor").header("Authorization", "Bearer $admin"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.nextCursor").doesNotExist())

        mvc.perform(
            get("/api/v1/admin/audit-logs?action=$action&actorId=99&from=2030-01-01T00:00:05Z&to=2030-01-01T00:00:20Z")
                .header("Authorization", "Bearer $admin"),
        ).andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(1))

        mvc.perform(get("/api/v1/admin/audit-logs?size=101").header("Authorization", "Bearer $admin"))
            .andExpect(status().isBadRequest)
    }
}
