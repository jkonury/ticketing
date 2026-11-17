package io.github.ticketing.member

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.expectStatus
import io.github.ticketing.member.domain.Role
import io.github.ticketing.member.repository.MemberRepository
import io.github.ticketing.member.service.AdminBootstrapRunner
import io.github.ticketing.member.service.AdminMemberService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@RecordApplicationEvents
class AdminMemberIntegrationTests(
    private val mockMvcTester: MockMvcTester,
    private val members: MemberRepository,
    private val adminMembers: AdminMemberService,
) : IntegrationTestSupport() {

    private val json = JsonMapper.builder().build()

    private fun email() = "user-${UUID.randomUUID()}@example.com"

    private fun body(vararg pairs: Pair<String, Any>) = json.writeValueAsString(mapOf(*pairs))

    private fun signUp(email: String, role: String = "USER"): MvcTestResult =
        mockMvcTester.post().uri("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
            .content(body("email" to email, "password" to "password123", "name" to "홍길동", "role" to role))
            .exchange()

    private fun login(email: String): Map<*, *> {
        val result = mockMvcTester.post().uri("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON)
            .content(body("email" to email, "password" to "password123"))
            .exchange()
            .expectStatus(HttpStatus.OK)
        return json.readValue(result.response.contentAsString, Map::class.java)
    }

    private fun refresh(token: String): MvcTestResult =
        mockMvcTester.post().uri("/api/v1/auth/tokens/refresh").contentType(MediaType.APPLICATION_JSON)
            .content(body("refreshToken" to token))
            .exchange()

    private fun bootstrap(email: String) = AdminBootstrapRunner(members, adminMembers, email).run(DefaultApplicationArguments())

    private fun memberId(email: String) = checkNotNull(members.findByEmail(email)?.id)

    /** 새 회원을 가입시켜 ADMIN으로 만들고 액세스 토큰(Bearer 값)을 돌려준다. */
    private fun admin(): Pair<Long, String> {
        val email = email()
        signUp(email).expectStatus(HttpStatus.CREATED)
        bootstrap(email)
        return memberId(email) to login(email)["accessToken"].toString()
    }

    private fun changeRole(token: String, id: Long, role: String): MvcTestResult =
        mockMvcTester.put().uri("/api/v1/admin/members/$id/role").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).content(body("role" to role))
            .exchange()

    @Test
    fun `가입 요청으로는 ADMIN 역할을 얻을 수 없다`() {
        assertThat(signUp(email(), "ADMIN"))
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson().extractingPath("$.code").isEqualTo("ROLE_NOT_ALLOWED")
    }

    @Test
    fun `부트스트랩은 지정한 회원을 ADMIN으로 승격하고 다시 실행해도 변화가 없다`(events: ApplicationEvents) {
        val email = email()
        signUp(email).expectStatus(HttpStatus.CREATED)

        bootstrap(email)
        bootstrap(email)

        assertThat(members.findByEmail(email)?.role).isEqualTo(Role.ADMIN)
        val changed = events.stream(MemberRoleChanged::class.java).filter { it.memberId == memberId(email) }.toList()
        assertThat(changed).hasSize(1)
        assertThat(changed.single().actorId).isNull()
        assertThat(changed.single().from).isEqualTo("USER")
        assertThat(changed.single().to).isEqualTo("ADMIN")
    }

    @Test
    fun `부트스트랩 대상이 비었거나 없는 회원이면 아무것도 하지 않는다`(events: ApplicationEvents) {
        bootstrap("")
        bootstrap(email())

        assertThat(events.stream(MemberRoleChanged::class.java).count()).isZero()
    }

    @Test
    fun `관리자가 아니면 목록과 역할 변경 모두 403이다`() {
        val email = email()
        signUp(email).expectStatus(HttpStatus.CREATED)
        val token = login(email)["accessToken"].toString()

        assertThat(mockMvcTester.get().uri("/api/v1/admin/members").header("Authorization", "Bearer $token"))
            .hasStatus(HttpStatus.FORBIDDEN)
        assertThat(changeRole(token, memberId(email), "ORGANIZER")).hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `관리자가 역할을 바꾸면 이벤트가 발행되고 기존 리프레시 토큰은 쓸 수 없다`(events: ApplicationEvents) {
        val (adminId, adminToken) = admin()
        val email = email()
        signUp(email).expectStatus(HttpStatus.CREATED)
        val memberId = memberId(email)
        val oldRefresh = login(email)["refreshToken"].toString()

        assertThat(changeRole(adminToken, memberId, "ORGANIZER"))
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.id").convertTo(Long::class.java).isEqualTo(memberId)
                extractingPath("$.role").isEqualTo("ORGANIZER")
            }

        assertThat(refresh(oldRefresh)).hasStatus(HttpStatus.UNAUTHORIZED)
        val changed = events.stream(MemberRoleChanged::class.java).filter { it.memberId == memberId }.toList().single()
        assertThat(changed.actorId).isEqualTo(adminId)
        assertThat(changed.from).isEqualTo("USER")
        assertThat(changed.to).isEqualTo("ORGANIZER")
    }

    @Test
    fun `같은 역할로 바꾸면 이벤트도 토큰 폐기도 없이 200이다`(events: ApplicationEvents) {
        val (_, adminToken) = admin()
        val email = email()
        signUp(email).expectStatus(HttpStatus.CREATED)
        val memberId = memberId(email)
        val refreshToken = login(email)["refreshToken"].toString()

        assertThat(changeRole(adminToken, memberId, "USER"))
            .hasStatus(HttpStatus.OK)
            .bodyJson().extractingPath("$.role").isEqualTo("USER")

        assertThat(refresh(refreshToken)).hasStatus(HttpStatus.OK)
        assertThat(events.stream(MemberRoleChanged::class.java).filter { it.memberId == memberId }.count()).isZero()
    }

    @Test
    fun `없는 회원의 역할을 바꾸면 404이다`() {
        val (_, adminToken) = admin()

        assertThat(changeRole(adminToken, Long.MAX_VALUE, "USER"))
            .hasStatus(HttpStatus.NOT_FOUND)
            .bodyJson().extractingPath("$.code").isEqualTo("MEMBER_NOT_FOUND")
    }

    @Test
    fun `마지막 관리자는 자기 자신도 강등할 수 없다`() {
        val (adminId, adminToken) = admin()
        members.findAll().filter {
            it.role == Role.ADMIN
        }.mapNotNull { it.id }.filter { it != adminId }.forEach { adminMembers.changeRole(null, it, Role.USER) }

        assertThat(changeRole(adminToken, adminId, "USER"))
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("LAST_ADMIN")

        assertThat(members.findById(adminId).get().role).isEqualTo(Role.ADMIN)
    }

    @Test
    fun `관리자가 둘이면 한 명을 강등할 수 있다`() {
        val (adminId, adminToken) = admin()
        val (otherId, _) = admin()

        assertThat(changeRole(adminToken, otherId, "USER")).hasStatus(HttpStatus.OK)

        assertThat(members.findById(adminId).get().role).isEqualTo(Role.ADMIN)
    }

    @Test
    fun `회원 목록은 ID 내림차순 커서로 페이지를 나누고 이메일로 거른다`() {
        val (_, adminToken) = admin()
        val tag = UUID.randomUUID().toString().take(8)
        repeat(3) { signUp("page-$tag-$it@example.com").expectStatus(HttpStatus.CREATED) }

        val first = mockMvcTester.get().uri("/api/v1/admin/members")
            .param("email", tag).param("size", "2").header("Authorization", "Bearer $adminToken")
            .exchange()
            .expectStatus(HttpStatus.OK)
        assertThat(first).bodyJson().extractingPath("$.items.length()").isEqualTo(2)
        val firstPage = json.readValue(first.response.contentAsString, Map::class.java)
        val nextCursor = firstPage["nextCursor"]
        assertThat(nextCursor).isNotNull()

        assertThat(
            mockMvcTester.get().uri("/api/v1/admin/members")
                .param("email", tag).param("size", "2").param("cursor", nextCursor.toString())
                .header("Authorization", "Bearer $adminToken"),
        )
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.items.length()").isEqualTo(1)
                extractingPath("$.items[0].email").isEqualTo("page-$tag-0@example.com")
                extractingPath("$.nextCursor").isNull()
            }
    }

    @Test
    fun `size가 범위를 벗어나면 400이다`() {
        val (_, adminToken) = admin()

        assertThat(mockMvcTester.get().uri("/api/v1/admin/members").param("size", "101").header("Authorization", "Bearer $adminToken"))
            .hasStatus(HttpStatus.BAD_REQUEST)
    }
}
