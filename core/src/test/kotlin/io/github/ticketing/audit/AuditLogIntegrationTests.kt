package io.github.ticketing.audit

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.audit.service.AdminActionRecorder
import io.github.ticketing.booking.BookingForceCancelled
import io.github.ticketing.member.MemberRoleChanged
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.transaction.support.TransactionTemplate
import ticketing.jooq.audit.tables.references.ADMIN_ACTION
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class AuditLogIntegrationTests(
    private val mockMvcTester: MockMvcTester,
    private val dsl: DSLContext,
    private val recorder: AdminActionRecorder,
    private val publisher: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
) : IntegrationTestSupport() {

    private val json = JsonMapper.builder().build()

    private fun count(eventId: UUID) = dsl.fetchCount(dsl.selectFrom("audit.admin_action").where("source_event_id = ?", eventId))

    private fun token(role: String): String {
        val email = "audit-${UUID.randomUUID()}@example.com"
        mockMvcTester.post()
            .uri("/api/v1/members")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123", "name" to "홍길동")))
            .assertThat()
            .hasStatus(HttpStatus.CREATED)

        dsl.execute("UPDATE member.member SET role = ? WHERE email = ?", role, email)
        val result = mockMvcTester.post()
            .uri("/api/v1/auth/tokens")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123")))
            .exchange()
        assertThat(result).hasStatus(HttpStatus.OK)
        val body = result.response.contentAsString
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
    fun `예매 강제 취소 이벤트를 재전달해도 관리자와 사유를 한 번 기록한다`() {
        val event = BookingForceCancelled(UUID.randomUUID(), 7, 123, "중복 예매", Instant.parse("2030-01-01T00:00:00Z"))

        repeat(2) { tx.executeWithoutResult { publisher.publishEvent(event) } }

        await().atMost(10, TimeUnit.SECONDS).untilAsserted {
            val rows = dsl.selectFrom(ADMIN_ACTION).where(ADMIN_ACTION.SOURCE_EVENT_ID.eq(event.eventId)).fetch()
            assertThat(rows).hasSize(1)
            val row = rows.single()
            assertThat(row.actorId).isEqualTo(7L)
            assertThat(row.action).isEqualTo("BOOKING_FORCE_CANCELLED")
            assertThat(row.targetType).isEqualTo("BOOKING")
            assertThat(row.targetId).isEqualTo("123")
            assertThat(json.readValue(requireNotNull(row.detail).data(), Map::class.java)).isEqualTo(mapOf("reason" to "중복 예매"))
        }
    }

    @Test
    fun `같은 이벤트를 두 번 처리해도 1건만 저장된다`() {
        val eventId = UUID.randomUUID()
        repeat(2) { recorder.record(eventId, null, "X", "T", "1", emptyMap(), Instant.now()) }

        assertThat(count(eventId)).isEqualTo(1)
    }

    @Test
    fun `비관리자는 감사 로그를 조회할 수 없다`() {
        mockMvcTester.get()
            .uri("/api/v1/admin/audit-logs")
            .header("Authorization", "Bearer ${token("USER")}")
            .assertThat()
            .hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `필터와 keyset 페이지네이션으로 조회한다`() {
        val admin = token("ADMIN")
        val action = "FILTER_${UUID.randomUUID()}"
        val base = Instant.parse("2030-01-01T00:00:00Z")
        repeat(3) { record(action, 99L, base.plusSeconds(it * 10L)) }
        record("OTHER", 99L, base)

        val result = mockMvcTester.get()
            .uri("/api/v1/admin/audit-logs?action=$action&size=2")
            .header("Authorization", "Bearer $admin")
            .exchange()

        assertThat(result)
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.items.length()").isEqualTo(2)
                extractingPath("$.items[0].detail.k").isEqualTo("v")
                extractingPath("$.items[0].actorId").isEqualTo(99)
            }
        val first = result.response.contentAsString
        val cursor = json.readValue(first, Map::class.java)["nextCursor"]
        assertThat(cursor).isNotNull()

        mockMvcTester.get()
            .uri("/api/v1/admin/audit-logs?action=$action&size=2&cursor=$cursor")
            .header("Authorization", "Bearer $admin")
            .assertThat()
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.items.length()").isEqualTo(1)
                extractingPath("$.nextCursor").isNull()
            }

        mockMvcTester.get()
            .uri("/api/v1/admin/audit-logs?action=$action&actorId=99&from=2030-01-01T00:00:05Z&to=2030-01-01T00:00:20Z")
            .header("Authorization", "Bearer $admin")
            .assertThat()
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.items.length()").isEqualTo(1)
            }

        mockMvcTester.get()
            .uri("/api/v1/admin/audit-logs?size=101")
            .header("Authorization", "Bearer $admin")
            .assertThat()
            .hasStatus(HttpStatus.BAD_REQUEST)
    }
}
