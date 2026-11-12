package io.github.ticketing.member.web

import io.github.ticketing.member.domain.Role
import io.github.ticketing.member.service.AuthService
import io.github.ticketing.member.service.MemberService
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class AuthController(private val memberService: MemberService, private val authService: AuthService) {

    @PostMapping("/members")
    fun signUp(@Valid @RequestBody request: SignUpRequest): ResponseEntity<MemberResponse> {
        val member = memberService.signUp(MemberService.SignUpCommand(request.email, request.password, request.name, request.role))
        return ResponseEntity.status(HttpStatus.CREATED).body(MemberResponse.from(member))
    }

    @GetMapping("/members/me")
    fun me(authentication: Authentication): MemberResponse = MemberResponse.from(memberService.get(authentication.name.toLong()))

    @PostMapping("/auth/tokens")
    fun login(@Valid @RequestBody request: LoginRequest): TokenResponse =
        TokenResponse.from(authService.login(request.email, request.password))

    @PostMapping("/auth/tokens/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): TokenResponse = TokenResponse.from(authService.refresh(request.refreshToken))

    @PostMapping("/auth/tokens/revoke")
    fun revoke(@Valid @RequestBody request: RefreshRequest): ResponseEntity<Void> {
        authService.revoke(request.refreshToken)
        return ResponseEntity.noContent().build()
    }
}

data class SignUpRequest(
    @field:Email @field:NotBlank @field:Size(max = 255) val email: String,
    @field:NotBlank @field:Size(min = 8, max = 64) val password: String,
    @field:NotBlank @field:Size(max = 50) val name: String,
    val role: Role = Role.USER,
)

data class LoginRequest(@field:NotBlank val email: String, @field:NotBlank val password: String)

data class RefreshRequest(@field:NotBlank val refreshToken: String)
