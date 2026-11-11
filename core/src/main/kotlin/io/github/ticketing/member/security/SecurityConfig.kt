package io.github.ticketing.member.security

import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import javax.crypto.spec.SecretKeySpec

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties::class)
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // 세션·쿠키를 쓰지 않는 Bearer 토큰 API다. 쿠키 세션의 CSRF 방어는 BFF가 맡는다 (ADR 0013)
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.POST, "/api/v1/members", "/api/v1/auth/tokens", "/api/v1/auth/tokens/refresh").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/payments/webhooks/fake-pg").permitAll()
                    .requestMatchers("/actuator/health/**").permitAll()
                    // 공연·회차 조회는 공개 API다 (catalog 모듈의 경로)
                    .requestMatchers(HttpMethod.GET, "/api/v1/performances/**", "/api/v1/schedules/**").permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer {
                it.jwt { jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()) }
                    .authenticationEntryPoint(authenticationEntryPoint())
                    .accessDeniedHandler(accessDeniedHandler())
            }
            .exceptionHandling {
                it.authenticationEntryPoint(authenticationEntryPoint()).accessDeniedHandler(accessDeniedHandler())
            }
            .httpBasic(AbstractHttpConfigurer<*, *>::disable)
            .formLogin(AbstractHttpConfigurer<*, *>::disable)
        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun jwtEncoder(properties: JwtProperties): JwtEncoder = NimbusJwtEncoder(ImmutableSecret(secretKey(properties)))

    @Bean
    fun jwtDecoder(properties: JwtProperties): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(secretKey(properties)).macAlgorithm(MacAlgorithm.HS256).build()

    private fun secretKey(properties: JwtProperties) = SecretKeySpec(properties.secret.toByteArray(), "HmacSHA256")

    // role 클레임(USER, ORGANIZER)을 ROLE_ 접두사가 붙은 권한으로 바꿔 hasRole(...)로 검사할 수 있게 한다
    private fun jwtAuthenticationConverter() = JwtAuthenticationConverter().apply {
        setJwtGrantedAuthoritiesConverter(
            JwtGrantedAuthoritiesConverter().apply {
                setAuthoritiesClaimName(JwtTokenProvider.ROLE_CLAIM)
                setAuthorityPrefix("ROLE_")
            },
        )
    }

    private fun authenticationEntryPoint() = AuthenticationEntryPoint { _, response, _ ->
        writeProblem(response, org.springframework.http.HttpStatus.UNAUTHORIZED.value(), "UNAUTHORIZED", "인증이 필요합니다.")
    }

    private fun accessDeniedHandler() = AccessDeniedHandler { _, response, _ ->
        writeProblem(response, org.springframework.http.HttpStatus.FORBIDDEN.value(), "FORBIDDEN", "접근 권한이 없습니다.")
    }

    // 필터 단계 오류는 @RestControllerAdvice가 닿지 않으므로 같은 ProblemDetail 형태로 직접 쓴다. 메시지는 고정 문자열이다
    private fun writeProblem(response: jakarta.servlet.http.HttpServletResponse, status: Int, code: String, detail: String) {
        response.status = status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write("""{"status":$status,"detail":"$detail","code":"$code"}""")
    }
}
