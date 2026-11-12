package io.github.ticketing.member

import io.github.ticketing.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

class MemberAuthIntegrationTests(private val mvc: MockMvc) : IntegrationTestSupport() {

    private val json = JsonMapper.builder().build()

    private fun email() = "user-${UUID.randomUUID()}@example.com"

    private fun body(vararg pairs: Pair<String, Any>) = json.writeValueAsString(mapOf(*pairs))

    private fun signUp(email: String, role: String = "USER") = mvc.perform(
        post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
            .content(body("email" to email, "password" to "password123", "name" to "홍길동", "role" to role)),
    )

    private fun login(email: String, password: String = "password123") = mvc.perform(
        post("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON).content(body("email" to email, "password" to password)),
    )

    private fun refresh(token: String) = mvc.perform(
        post("/api/v1/auth/tokens/refresh").contentType(MediaType.APPLICATION_JSON).content(body("refreshToken" to token)),
    )

    private fun revoke(token: String) = mvc.perform(
        post("/api/v1/auth/tokens/revoke").contentType(MediaType.APPLICATION_JSON).content(body("refreshToken" to token)),
    )

    private fun tokens(email: String): Map<String, Any> {
        signUp(email).andExpect(status().isCreated)
        val result = login(email).andExpect(status().isOk).andReturn().response.contentAsString
        @Suppress("UNCHECKED_CAST")
        return json.readValue(result, Map::class.java) as Map<String, Any>
    }

    @Test
    fun `가입한 회원은 로그인해서 액세스 토큰으로 내 정보를 조회한다`() {
        val email = email()
        val tokens = tokens(email)

        mvc.perform(get("/api/v1/members/me").header("Authorization", "Bearer ${tokens["accessToken"]}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.role").value("USER"))
    }

    @Test
    fun `같은 이메일로 다시 가입하면 409와 코드를 돌려준다`() {
        val email = email()
        signUp(email).andExpect(status().isCreated)

        signUp(email.uppercase()).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"))
    }

    @Test
    fun `비밀번호가 틀리거나 없는 이메일이면 같은 오류로 401을 돌려준다`() {
        val email = email()
        signUp(email).andExpect(status().isCreated)

        login(email, "wrong-password").andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
        login(email()).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
    }

    @Test
    fun `토큰이 없거나 잘못되면 401이다`() {
        mvc.perform(get("/api/v1/members/me")).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        mvc.perform(get("/api/v1/members/me").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `리프레시 토큰으로 새 토큰을 받고 이전 리프레시 토큰은 다시 쓸 수 없다`() {
        val first = tokens(email())["refreshToken"] as String

        val rotated = refresh(first).andExpect(status().isOk).andReturn().response.contentAsString
        val second = (json.readValue(rotated, Map::class.java)["refreshToken"]) as String

        refresh(first).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
        // 재사용이 감지되면 그 회원의 모든 리프레시 토큰이 폐기된다
        refresh(second).andExpect(status().isUnauthorized)
    }

    @Test
    fun `입력이 올바르지 않으면 400이다`() {
        mvc.perform(
            post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .content(body("email" to "not-an-email", "password" to "short", "name" to "")),
        ).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `리프레시 토큰 폐기는 인증 없이 멱등이며 해당 토큰만 폐기한다`() {
        val email = email()
        val first = tokens(email)["refreshToken"] as String
        val second = json.readValue(login(email).andReturn().response.contentAsString, Map::class.java)["refreshToken"] as String

        revoke(first).andExpect(status().isNoContent)
        revoke(first).andExpect(status().isNoContent)
        revoke("unknown-token").andExpect(status().isNoContent)
        refresh(second).andExpect(status().isOk)
        refresh(first).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
        revoke("").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }
}
