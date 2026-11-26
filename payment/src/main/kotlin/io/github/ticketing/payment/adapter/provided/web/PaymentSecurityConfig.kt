package io.github.ticketing.payment.adapter.provided.web

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import javax.crypto.spec.SecretKeySpec

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(PaymentJwtProperties::class)
internal class PaymentSecurityConfig {

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // 세션·쿠키를 쓰지 않는 Bearer 토큰 API다. 쿠키 세션의 CSRF 방어는 BFF가 맡는다 (ADR 0013)
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.POST, "/api/v1/payments/webhooks/fake-pg").permitAll()
                    .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                    .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs.yaml").permitAll()
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
    fun jwtDecoder(properties: PaymentJwtProperties): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(SecretKeySpec(properties.secret.toByteArray(), "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build()

    // role 클레임(USER, ORGANIZER, ADMIN)을 ROLE_ 접두사가 붙은 권한으로 바꿔 hasRole(...)로 검사할 수 있게 한다
    private fun jwtAuthenticationConverter() = JwtAuthenticationConverter().apply {
        setJwtGrantedAuthoritiesConverter(
            JwtGrantedAuthoritiesConverter().apply {
                setAuthoritiesClaimName("role")
                setAuthorityPrefix("ROLE_")
            },
        )
    }

    private fun authenticationEntryPoint() = AuthenticationEntryPoint { _, response, _ ->
        writeProblem(response, HttpStatus.UNAUTHORIZED.value(), "UNAUTHORIZED", "인증이 필요합니다.")
    }

    private fun accessDeniedHandler() = AccessDeniedHandler { _, response, _ ->
        writeProblem(response, HttpStatus.FORBIDDEN.value(), "FORBIDDEN", "접근 권한이 없습니다.")
    }

    // 필터 단계 오류는 @RestControllerAdvice가 닿지 않으므로 같은 ProblemDetail 형태로 직접 쓴다. 메시지는 고정 문자열이다
    private fun writeProblem(response: jakarta.servlet.http.HttpServletResponse, status: Int, code: String, detail: String) {
        response.status = status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write("""{"status":$status,"detail":"$detail","code":"$code"}""")
    }
}

@ConfigurationProperties("ticketing.jwt")
internal class PaymentJwtProperties(val secret: String) {
    init {
        require(secret.toByteArray().size >= MIN_SECRET_BYTES) { "ticketing.jwt.secret은 32바이트 이상이어야 합니다." }
    }
}

private const val MIN_SECRET_BYTES = 32
