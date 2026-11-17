package io.github.ticketing.coupon

import io.github.ticketing.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

class CouponApiIntegrationTests(private val mvc: MockMvc, private val dsl: DSLContext) : IntegrationTestSupport() {

    private val json = JsonMapper.builder().build()

    private class Account(val id: Long, val token: String)

    private fun account(role: String): Account {
        val email = "coupon-${UUID.randomUUID()}@example.com"
        mvc.perform(
            post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123", "name" to "홍길동"))),
        ).andExpect(status().isCreated)
        dsl.execute("UPDATE member.member SET role = ? WHERE email = ?", role, email)
        val id = dsl.fetchOne("SELECT id FROM member.member WHERE email = ?", email)!!.get("id", Long::class.java)
        val body = mvc.perform(
            post("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123"))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return Account(id, json.readValue(body, Map::class.java)["accessToken"].toString())
    }

    private fun definitionBody(
        issueMode: String = "UNLIMITED",
        validFrom: Instant = Instant.now().minus(1, ChronoUnit.DAYS),
        validUntil: Instant = Instant.now().plus(30, ChronoUnit.DAYS),
        totalQuantity: Int? = null,
    ) = mapOf(
        "name" to "신규 가입 쿠폰",
        "description" to "설명",
        "discountType" to "RATE",
        "discountValue" to 10,
        "maxDiscount" to 5000,
        "minOrderAmount" to 10000,
        "performanceId" to null,
        "validFrom" to validFrom.toString(),
        "validUntil" to validUntil.toString(),
        "issueMode" to issueMode,
        "totalQuantity" to totalQuantity,
    )

    private fun send(
        token: String,
        request: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder,
        body: Any? = null,
    ) = mvc.perform(
        request.header("Authorization", "Bearer $token").apply {
            if (body != null) contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
        },
    )

    private fun createDefinition(admin: Account, body: Map<String, Any?> = definitionBody()): Long {
        val response = send(
            admin.token,
            post("/api/v1/admin/coupons"),
            body,
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return (json.readValue(response, Map::class.java)["id"] as Number).toLong()
    }

    private fun grant(admin: Account, definitionId: Long, vararg memberIds: Long): ResultActions =
        send(admin.token, post("/api/v1/admin/coupons/$definitionId/grants"), mapOf("memberIds" to memberIds.toList()))

    private fun claimable(member: Account): List<Map<*, *>> {
        val body = send(member.token, get("/api/v1/coupons")).andExpect(status().isOk).andReturn().response.contentAsString
        return json.readValue(body, List::class.java).filterIsInstance<Map<*, *>>()
    }

    private fun claimed(member: Account, definitionId: Long) =
        claimable(member).single { (it["id"] as Number).toLong() == definitionId }["claimed"] as Boolean

    private fun auditCount(action: String, targetId: Any) =
        dsl.fetchCount(dsl.selectFrom("audit.admin_action").where("action = ? AND target_id = ?", action, targetId.toString()))

    @Test
    fun `관리자는 쿠폰 정의를 만들고 단건과 목록으로 현황을 조회한다`() {
        val admin = account("ADMIN")

        val id = createDefinition(admin)

        send(admin.token, get("/api/v1/admin/coupons/$id")).andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.discountType").value("RATE"))
            .andExpect(jsonPath("$.issueStartedAt").value(null as Any?))
            .andExpect(jsonPath("$.stats.issued").value(0))
        send(admin.token, get("/api/v1/admin/coupons?size=1")).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].id").value(id))
            .andExpect(jsonPath("$.items[0].stats.used").value(0))
        send(admin.token, get("/api/v1/admin/coupons?size=101")).andExpect(status().isBadRequest)
        send(
            admin.token,
            get("/api/v1/admin/coupons/0"),
        ).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"))
    }

    @Test
    fun `잘못된 정의 요청은 400이다`() {
        val admin = account("ADMIN")

        send(admin.token, post("/api/v1/admin/coupons"), definitionBody() + ("name" to " "))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        send(admin.token, post("/api/v1/admin/coupons"), definitionBody(issueMode = "LIMITED"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        send(admin.token, post("/api/v1/admin/coupons"), definitionBody(validUntil = Instant.now().minus(2, ChronoUnit.DAYS)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `발급이 시작되면 할인 조건 수정은 잠기고 이름과 기간 연장은 가능하다`() {
        val admin = account("ADMIN")
        val member = account("USER")
        val id = createDefinition(admin)

        send(admin.token, patch("/api/v1/admin/coupons/$id"), mapOf("discountValue" to 20)).andExpect(status().isOk)
            .andExpect(jsonPath("$.discountValue").value(20))
        grant(admin, id, member.id).andExpect(status().isOk)

        send(admin.token, patch("/api/v1/admin/coupons/$id"), mapOf("discountValue" to 30))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("COUPON_DEFINITION_LOCKED"))
        send(admin.token, patch("/api/v1/admin/coupons/$id"), mapOf("validUntil" to Instant.now().plus(10, ChronoUnit.DAYS).toString()))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("COUPON_VALID_UNTIL_SHORTENED"))
        send(
            admin.token,
            patch("/api/v1/admin/coupons/$id"),
            mapOf("name" to "새 이름", "validUntil" to Instant.now().plus(60, ChronoUnit.DAYS).toString()),
        ).andExpect(status().isOk).andExpect(jsonPath("$.name").value("새 이름")).andExpect(jsonPath("$.issueStartedAt").isNotEmpty)
        await().atMost(10, TimeUnit.SECONDS).untilAsserted { assertThat(auditCount("COUPON_DEFINITION_UPDATED", id)).isEqualTo(2) }
    }

    @Test
    fun `중지는 멱등이고 중지된 정의로는 지급하거나 받을 수 없다`() {
        val admin = account("ADMIN")
        val member = account("USER")
        val id = createDefinition(admin)

        repeat(2) {
            send(
                admin.token,
                post("/api/v1/admin/coupons/$id/stop"),
            ).andExpect(status().isOk).andExpect(jsonPath("$.status").value("STOPPED"))
        }

        grant(admin, id, member.id).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("COUPON_NOT_ISSUABLE"))
        send(member.token, post("/api/v1/coupons/$id/claims")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("COUPON_NOT_ISSUABLE"))
        await().atMost(10, TimeUnit.SECONDS).untilAsserted { assertThat(auditCount("COUPON_DEFINITION_STOPPED", id)).isEqualTo(1) }
    }

    @Test
    fun `직접 지급은 새로 받은 회원과 이미 받은 회원을 나눠 알리고 현황과 발급 목록에 반영된다`() {
        val admin = account("ADMIN")
        val first = account("USER")
        val second = account("USER")
        val id = createDefinition(admin, definitionBody(issueMode = "DIRECT"))
        grant(admin, id, first.id).andExpect(status().isOk)

        grant(admin, id, first.id, second.id).andExpect(status().isOk)
            .andExpect(jsonPath("$.granted[0]").value(second.id))
            .andExpect(jsonPath("$.alreadyClaimed[0]").value(first.id))

        send(admin.token, get("/api/v1/admin/coupons/$id")).andExpect(jsonPath("$.stats.issued").value(2))
        send(admin.token, get("/api/v1/admin/coupons/$id/issued?size=1")).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].memberId").value(second.id))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty)
        send(admin.token, get("/api/v1/admin/coupons/$id/issued?status=REVOKED")).andExpect(jsonPath("$.items.length()").value(0))
        grant(admin, id).andExpect(status().isBadRequest)
        await().atMost(10, TimeUnit.SECONDS).untilAsserted { assertThat(auditCount("COUPONS_GRANTED", id)).isEqualTo(2) }
    }

    @Test
    fun `발급된 쿠폰은 회수할 수 있고 이미 회수했으면 409다`() {
        val admin = account("ADMIN")
        val member = account("USER")
        val id = createDefinition(admin)
        grant(admin, id, member.id).andExpect(status().isOk)
        val issuedId = (
            json.readValue(
                send(admin.token, get("/api/v1/admin/coupons/$id/issued")).andReturn().response.contentAsString,
                Map::class.java,
            )["items"] as List<*>
            ).let { ((it.single() as Map<*, *>)["id"] as Number).toLong() }

        send(admin.token, post("/api/v1/admin/issued-coupons/$issuedId/revoke")).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REVOKED"))
        send(admin.token, post("/api/v1/admin/issued-coupons/$issuedId/revoke")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("COUPON_NOT_REVOCABLE"))
        send(admin.token, post("/api/v1/admin/issued-coupons/0/revoke")).andExpect(status().isNotFound)

        send(admin.token, get("/api/v1/admin/coupons/$id")).andExpect(jsonPath("$.stats.revoked").value(1))
        await().atMost(10, TimeUnit.SECONDS).untilAsserted { assertThat(auditCount("COUPON_REVOKED", issuedId)).isEqualTo(1) }
    }

    @Test
    fun `비관리자는 관리자 API를 호출할 수 없다`() {
        val member = account("USER")

        send(member.token, post("/api/v1/admin/coupons"), definitionBody()).andExpect(status().isForbidden)
        send(member.token, get("/api/v1/admin/coupons")).andExpect(status().isForbidden)
        send(member.token, post("/api/v1/admin/coupons/1/grants"), mapOf("memberIds" to listOf(1))).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/coupons")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `UNLIMITED 쿠폰은 한 번만 받을 수 있고 받은 쿠폰은 내 쿠폰에 보인다`() {
        val admin = account("ADMIN")
        val member = account("USER")
        val id = createDefinition(admin)

        assertThat(claimed(member, id)).isFalse()
        send(member.token, post("/api/v1/coupons/$id/claims")).andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("ISSUED")).andExpect(jsonPath("$.memberId").value(member.id))
        send(member.token, post("/api/v1/coupons/$id/claims")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("COUPON_ALREADY_CLAIMED"))

        assertThat(claimed(member, id)).isTrue()
        send(member.token, get("/api/v1/me/coupons")).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].definitionId").value(id))
            .andExpect(jsonPath("$[0].definition.id").value(id))
            .andExpect(jsonPath("$[0].definition.name").value("신규 가입 쿠폰"))
        send(admin.token, get("/api/v1/admin/coupons/$id")).andExpect(jsonPath("$.issueStartedAt").isNotEmpty)
    }

    @Test
    fun `DIRECT와 LIMITED 쿠폰은 직접 받을 수 없고 목록의 DIRECT는 노출되지 않는다`() {
        val admin = account("ADMIN")
        val member = account("USER")
        val direct = createDefinition(admin, definitionBody(issueMode = "DIRECT"))
        val limited = createDefinition(admin, definitionBody(issueMode = "LIMITED", totalQuantity = 10))

        send(member.token, post("/api/v1/coupons/$direct/claims")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("COUPON_NOT_ISSUABLE"))
        send(member.token, post("/api/v1/coupons/$limited/claims")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("COUPON_NOT_ISSUABLE"))
        send(member.token, post("/api/v1/coupons/0/claims")).andExpect(status().isNotFound)

        val listedIds = claimable(member).map { (it["id"] as Number).toLong() }
        assertThat(listedIds).contains(limited).doesNotContain(direct)
    }

    @Test
    fun `유효 기간이 지난 ISSUED 쿠폰은 내 쿠폰에서 EXPIRED로 보인다`() {
        val admin = account("ADMIN")
        val member = account("USER")
        val expired = definitionBody(
            issueMode = "DIRECT",
            validFrom = Instant.now().minus(10, ChronoUnit.DAYS),
            validUntil = Instant.now().minus(1, ChronoUnit.DAYS),
        )
        val id = createDefinition(admin, expired)
        grant(admin, id, member.id).andExpect(status().isOk)

        send(member.token, get("/api/v1/me/coupons")).andExpect(jsonPath("$[0].status").value("EXPIRED"))
        send(admin.token, get("/api/v1/admin/coupons/$id/issued?status=EXPIRED")).andExpect(jsonPath("$.items.length()").value(1))
        send(admin.token, get("/api/v1/admin/coupons/$id/issued?status=ISSUED")).andExpect(jsonPath("$.items.length()").value(0))
    }

    @Test
    fun `정의 생성은 감사 로그 1건으로 기록된다`() {
        val admin = account("ADMIN")

        val id = createDefinition(admin)

        await().atMost(10, TimeUnit.SECONDS).untilAsserted { assertThat(auditCount("COUPON_DEFINITION_CREATED", id)).isEqualTo(1) }
        val row = dsl.fetchOne(
            "SELECT actor_id, target_type FROM audit.admin_action WHERE action = ? AND target_id = ?",
            "COUPON_DEFINITION_CREATED",
            id.toString(),
        )!!
        assertThat(row["actor_id"]).isEqualTo(admin.id)
        assertThat(row["target_type"]).isEqualTo("COUPON_DEFINITION")
    }
}
