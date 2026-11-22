package io.github.ticketing.member

import io.github.ticketing.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

class MemberAuthIntegrationTests(private val mockMvcTester: MockMvcTester) : IntegrationTestSupport() {

    private val json = JsonMapper.builder().build()

    private fun email() = "user-${UUID.randomUUID()}@example.com"

    private fun body(vararg pairs: Pair<String, Any>) = json.writeValueAsString(mapOf(*pairs))

    private fun signUp(email: String, role: String = "USER"): MvcTestResult =
        mockMvcTester.post().uri("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
            .content(body("email" to email, "password" to "password123", "name" to "홍길동", "role" to role))
            .exchange()

    private fun login(email: String, password: String = "password123"): MvcTestResult =
        mockMvcTester.post().uri("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON)
            .content(body("email" to email, "password" to password))
            .exchange()

    private fun refresh(token: String): MvcTestResult =
        mockMvcTester.post().uri("/api/v1/auth/tokens/refresh").contentType(MediaType.APPLICATION_JSON)
            .content(body("refreshToken" to token))
            .exchange()

    private fun revoke(token: String): MvcTestResult =
        mockMvcTester.post().uri("/api/v1/auth/tokens/revoke").contentType(MediaType.APPLICATION_JSON)
            .content(body("refreshToken" to token))
            .exchange()

    private fun tokens(email: String): Map<String, Any> {
        assertThat(signUp(email)).hasStatus(HttpStatus.CREATED)
        val result = login(email)
        assertThat(result).hasStatus(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        return json.readValue(result.response.contentAsString, Map::class.java) as Map<String, Any>
    }

    @Test
    fun `가입한 회원은 로그인해서 액세스 토큰으로 내 정보를 조회한다`() {
        val email = email()
        val tokens = tokens(email)

        mockMvcTester.get().uri("/api/v1/members/me").header("Authorization", "Bearer ${tokens["accessToken"]}")
            .assertThat()
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.email").isEqualTo(email)
                extractingPath("$.role").isEqualTo("USER")
            }
    }

    @Test
    fun `같은 이메일로 다시 가입하면 409와 코드를 돌려준다`() {
        val email = email()
        assertThat(signUp(email)).hasStatus(HttpStatus.CREATED)

        assertThat(signUp(email.uppercase()))
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("EMAIL_ALREADY_EXISTS")
    }

    @Test
    fun `비밀번호가 틀리거나 없는 이메일이면 같은 오류로 401을 돌려준다`() {
        val email = email()
        assertThat(signUp(email)).hasStatus(HttpStatus.CREATED)

        assertThat(login(email, "wrong-password"))
            .hasStatus(HttpStatus.UNAUTHORIZED)
            .bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS")
        assertThat(login(email()))
            .hasStatus(HttpStatus.UNAUTHORIZED)
            .bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS")
    }

    @Test
    fun `토큰이 없거나 잘못되면 401이다`() {
        mockMvcTester.get().uri("/api/v1/members/me")
            .assertThat()
            .hasStatus(HttpStatus.UNAUTHORIZED)
            .bodyJson().extractingPath("$.code").isEqualTo("UNAUTHORIZED")
        mockMvcTester.get().uri("/api/v1/members/me").header("Authorization", "Bearer invalid")
            .assertThat()
            .hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `결제 웹훅 경로는 core가 공개하지 않아 인증 없이는 401이다`() {
        mockMvcTester.post().uri("/api/v1/payments/webhooks/fake-pg")
            .assertThat()
            .hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `리프레시 토큰으로 새 토큰을 받고 이전 리프레시 토큰은 다시 쓸 수 없다`() {
        val first = tokens(email())["refreshToken"] as String

        val rotated = refresh(first)
        assertThat(rotated).hasStatus(HttpStatus.OK)
        val second = (json.readValue(rotated.response.contentAsString, Map::class.java)["refreshToken"]) as String

        assertThat(refresh(first))
            .hasStatus(HttpStatus.UNAUTHORIZED)
            .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REFRESH_TOKEN")
        // 재사용이 감지되면 그 회원의 모든 리프레시 토큰이 폐기된다
        assertThat(refresh(second)).hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `입력이 올바르지 않으면 400이다`() {
        mockMvcTester.post().uri("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
            .content(body("email" to "not-an-email", "password" to "short", "name" to ""))
            .assertThat()
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST")
    }

    @Test
    fun `리프레시 토큰 폐기는 인증 없이 멱등이며 해당 토큰만 폐기한다`() {
        val email = email()
        val first = tokens(email)["refreshToken"] as String
        val loginResult = login(email)
        assertThat(loginResult).hasStatus(HttpStatus.OK)
        val second = json.readValue(loginResult.response.contentAsString, Map::class.java)["refreshToken"] as String

        assertThat(revoke(first)).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(revoke(first)).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(revoke("unknown-token")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(refresh(second)).hasStatus(HttpStatus.OK)
        assertThat(refresh(first))
            .hasStatus(HttpStatus.UNAUTHORIZED)
            .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REFRESH_TOKEN")
        assertThat(revoke(""))
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST")
    }
}
