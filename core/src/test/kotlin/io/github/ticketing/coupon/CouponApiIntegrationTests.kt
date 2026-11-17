package io.github.ticketing.coupon

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.expectStatus
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

class CouponApiIntegrationTests(private val mockMvcTester: MockMvcTester, private val dsl: DSLContext) : IntegrationTestSupport() {

    private val json = JsonMapper.builder().build()

    private class Account(val id: Long, val token: String)

    private fun get(path: String) = mockMvcTester.get().uri(path)
    private fun post(path: String) = mockMvcTester.post().uri(path)
    private fun patch(path: String) = mockMvcTester.patch().uri(path)

    private fun account(role: String): Account {
        val email = "coupon-${UUID.randomUUID()}@example.com"
        mockMvcTester.post().uri("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123", "name" to "홍길동")))
            .exchange()
            .expectStatus(HttpStatus.CREATED)
        dsl.execute("UPDATE member.member SET role = ? WHERE email = ?", role, email)
        val id = dsl.fetchOne("SELECT id FROM member.member WHERE email = ?", email)!!.get("id", Long::class.java)
        val body = mockMvcTester.post().uri("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(mapOf("email" to email, "password" to "password123")))
            .exchange()
            .expectStatus(HttpStatus.OK)
            .response.contentAsString
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

    private fun send(token: String, request: MockMvcTester.MockMvcRequestBuilder, body: Any? = null): MvcTestResult {
        request.header("Authorization", "Bearer $token")
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
        return request.exchange()
    }

    private fun createDefinition(admin: Account, body: Map<String, Any?> = definitionBody()): Long {
        val response = send(
            admin.token,
            post("/api/v1/admin/coupons"),
            body,
        ).expectStatus(HttpStatus.CREATED).response.contentAsString
        return (json.readValue(response, Map::class.java)["id"] as Number).toLong()
    }

    private fun grant(admin: Account, definitionId: Long, vararg memberIds: Long): MvcTestResult =
        send(admin.token, post("/api/v1/admin/coupons/$definitionId/grants"), mapOf("memberIds" to memberIds.toList()))

    private fun claimable(member: Account): List<Map<*, *>> {
        val body = send(member.token, get("/api/v1/coupons")).expectStatus(HttpStatus.OK).response.contentAsString
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

        assertThat(send(admin.token, get("/api/v1/admin/coupons/$id")))
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.id").convertTo(Long::class.java).isEqualTo(id)
                extractingPath("$.status").isEqualTo("ACTIVE")
                extractingPath("$.discountType").isEqualTo("RATE")
                extractingPath("$.issueStartedAt").isNull()
                extractingPath("$.stats.issued").isEqualTo(0)
            }
        assertThat(send(admin.token, get("/api/v1/admin/coupons?size=1")))
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.items[0].id").convertTo(Long::class.java).isEqualTo(id)
                extractingPath("$.items[0].stats.used").isEqualTo(0)
            }
        assertThat(send(admin.token, get("/api/v1/admin/coupons?size=101"))).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(send(admin.token, get("/api/v1/admin/coupons/0")))
            .hasStatus(HttpStatus.NOT_FOUND)
            .bodyJson().extractingPath("$.code").isEqualTo("COUPON_NOT_FOUND")
    }

    @Test
    fun `잘못된 정의 요청은 400이다`() {
        val admin = account("ADMIN")

        assertThat(send(admin.token, post("/api/v1/admin/coupons"), definitionBody() + ("name" to " ")))
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST")
        assertThat(send(admin.token, post("/api/v1/admin/coupons"), definitionBody(issueMode = "LIMITED")))
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST")
        assertThat(send(admin.token, post("/api/v1/admin/coupons"), definitionBody(validUntil = Instant.now().minus(2, ChronoUnit.DAYS))))
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST")
    }

    @Test
    fun `발급이 시작되면 할인 조건 수정은 잠기고 이름과 기간 연장은 가능하다`() {
        val admin = account("ADMIN")
        val member = account("USER")
        val id = createDefinition(admin)

        assertThat(send(admin.token, patch("/api/v1/admin/coupons/$id"), mapOf("discountValue" to 20)))
            .hasStatus(HttpStatus.OK)
            .bodyJson().extractingPath("$.discountValue").isEqualTo(20)
        grant(admin, id, member.id).expectStatus(HttpStatus.OK)

        assertThat(send(admin.token, patch("/api/v1/admin/coupons/$id"), mapOf("discountValue" to 30)))
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("COUPON_DEFINITION_LOCKED")
        assertThat(
            send(
                admin.token,
                patch("/api/v1/admin/coupons/$id"),
                mapOf("validUntil" to Instant.now().plus(10, ChronoUnit.DAYS).toString()),
            ),
        )
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("COUPON_VALID_UNTIL_SHORTENED")
        assertThat(
            send(
                admin.token,
                patch("/api/v1/admin/coupons/$id"),
                mapOf("name" to "새 이름", "validUntil" to Instant.now().plus(60, ChronoUnit.DAYS).toString()),
            ),
        )
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.name").isEqualTo("새 이름")
                extractingPath("$.issueStartedAt").isNotEmpty()
            }
        await().atMost(10, TimeUnit.SECONDS).untilAsserted { assertThat(auditCount("COUPON_DEFINITION_UPDATED", id)).isEqualTo(2) }
    }

    @Test
    fun `중지는 멱등이고 중지된 정의로는 지급하거나 받을 수 없다`() {
        val admin = account("ADMIN")
        val member = account("USER")
        val id = createDefinition(admin)

        repeat(2) {
            assertThat(send(admin.token, post("/api/v1/admin/coupons/$id/stop")))
                .hasStatus(HttpStatus.OK)
                .bodyJson().extractingPath("$.status").isEqualTo("STOPPED")
        }

        assertThat(grant(admin, id, member.id))
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("COUPON_NOT_ISSUABLE")
        assertThat(send(member.token, post("/api/v1/coupons/$id/claims")))
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("COUPON_NOT_ISSUABLE")
        await().atMost(10, TimeUnit.SECONDS).untilAsserted { assertThat(auditCount("COUPON_DEFINITION_STOPPED", id)).isEqualTo(1) }
    }

    @Test
    fun `직접 지급은 새로 받은 회원과 이미 받은 회원을 나눠 알리고 현황과 발급 목록에 반영된다`() {
        val admin = account("ADMIN")
        val first = account("USER")
        val second = account("USER")
        val id = createDefinition(admin, definitionBody(issueMode = "DIRECT"))
        grant(admin, id, first.id).expectStatus(HttpStatus.OK)

        assertThat(grant(admin, id, first.id, second.id))
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.granted[0]").convertTo(Long::class.java).isEqualTo(second.id)
                extractingPath("$.alreadyClaimed[0]").convertTo(Long::class.java).isEqualTo(first.id)
            }

        assertThat(send(admin.token, get("/api/v1/admin/coupons/$id"))).bodyJson().extractingPath("$.stats.issued").isEqualTo(2)
        assertThat(send(admin.token, get("/api/v1/admin/coupons/$id/issued?size=1")))
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.items.length()").isEqualTo(1)
                extractingPath("$.items[0].memberId").convertTo(Long::class.java).isEqualTo(second.id)
                extractingPath("$.nextCursor").isNotEmpty()
            }
        assertThat(send(admin.token, get("/api/v1/admin/coupons/$id/issued?status=REVOKED")))
            .bodyJson().extractingPath("$.items.length()").isEqualTo(0)
        assertThat(grant(admin, id)).hasStatus(HttpStatus.BAD_REQUEST)
        await().atMost(10, TimeUnit.SECONDS).untilAsserted { assertThat(auditCount("COUPONS_GRANTED", id)).isEqualTo(2) }
    }

    @Test
    fun `발급된 쿠폰은 회수할 수 있고 이미 회수했으면 409다`() {
        val admin = account("ADMIN")
        val member = account("USER")
        val id = createDefinition(admin)
        grant(admin, id, member.id).expectStatus(HttpStatus.OK)
        val issuedId = (
            json.readValue(
                send(admin.token, get("/api/v1/admin/coupons/$id/issued")).response.contentAsString,
                Map::class.java,
            )["items"] as List<*>
            ).let { ((it.single() as Map<*, *>)["id"] as Number).toLong() }

        assertThat(send(admin.token, post("/api/v1/admin/issued-coupons/$issuedId/revoke")))
            .hasStatus(HttpStatus.OK)
            .bodyJson().extractingPath("$.status").isEqualTo("REVOKED")
        assertThat(send(admin.token, post("/api/v1/admin/issued-coupons/$issuedId/revoke")))
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("COUPON_NOT_REVOCABLE")
        assertThat(send(admin.token, post("/api/v1/admin/issued-coupons/0/revoke"))).hasStatus(HttpStatus.NOT_FOUND)

        assertThat(send(admin.token, get("/api/v1/admin/coupons/$id"))).bodyJson().extractingPath("$.stats.revoked").isEqualTo(1)
        await().atMost(10, TimeUnit.SECONDS).untilAsserted { assertThat(auditCount("COUPON_REVOKED", issuedId)).isEqualTo(1) }
    }

    @Test
    fun `비관리자는 관리자 API를 호출할 수 없다`() {
        val member = account("USER")

        assertThat(send(member.token, post("/api/v1/admin/coupons"), definitionBody())).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(send(member.token, get("/api/v1/admin/coupons"))).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(send(member.token, post("/api/v1/admin/coupons/1/grants"), mapOf("memberIds" to listOf(1))))
            .hasStatus(HttpStatus.FORBIDDEN)
        assertThat(get("/api/v1/admin/coupons")).hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `UNLIMITED 쿠폰은 한 번만 받을 수 있고 받은 쿠폰은 내 쿠폰에 보인다`() {
        val admin = account("ADMIN")
        val member = account("USER")
        val id = createDefinition(admin)

        assertThat(claimed(member, id)).isFalse()
        assertThat(send(member.token, post("/api/v1/coupons/$id/claims")))
            .hasStatus(HttpStatus.CREATED)
            .bodyJson().apply {
                extractingPath("$.status").isEqualTo("ISSUED")
                extractingPath("$.memberId").convertTo(Long::class.java).isEqualTo(member.id)
            }
        assertThat(send(member.token, post("/api/v1/coupons/$id/claims")))
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("COUPON_ALREADY_CLAIMED")

        assertThat(claimed(member, id)).isTrue()
        assertThat(send(member.token, get("/api/v1/me/coupons")))
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.length()").isEqualTo(1)
                extractingPath("$[0].definitionId").convertTo(Long::class.java).isEqualTo(id)
                extractingPath("$[0].definition.id").convertTo(Long::class.java).isEqualTo(id)
                extractingPath("$[0].definition.name").isEqualTo("신규 가입 쿠폰")
            }
        assertThat(send(admin.token, get("/api/v1/admin/coupons/$id"))).bodyJson().extractingPath("$.issueStartedAt").isNotEmpty()
    }

    @Test
    fun `DIRECT 쿠폰은 직접 받을 수 없고 LIMITED는 받을 수 있으며 목록의 DIRECT는 노출되지 않는다`() {
        val admin = account("ADMIN")
        val member = account("USER")
        val direct = createDefinition(admin, definitionBody(issueMode = "DIRECT"))
        val limited = createDefinition(admin, definitionBody(issueMode = "LIMITED", totalQuantity = 10))

        assertThat(send(member.token, post("/api/v1/coupons/$direct/claims")))
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("COUPON_NOT_ISSUABLE")
        assertThat(send(member.token, post("/api/v1/coupons/$limited/claims"))).hasStatus(HttpStatus.CREATED)
        assertThat(send(member.token, post("/api/v1/coupons/0/claims"))).hasStatus(HttpStatus.NOT_FOUND)

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
        grant(admin, id, member.id).expectStatus(HttpStatus.OK)

        assertThat(send(member.token, get("/api/v1/me/coupons"))).bodyJson().extractingPath("$[0].status").isEqualTo("EXPIRED")
        assertThat(send(admin.token, get("/api/v1/admin/coupons/$id/issued?status=EXPIRED")))
            .bodyJson().extractingPath("$.items.length()").isEqualTo(1)
        assertThat(send(admin.token, get("/api/v1/admin/coupons/$id/issued?status=ISSUED")))
            .bodyJson().extractingPath("$.items.length()").isEqualTo(0)
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
