package io.github.ticketing.booking.adapter.provided.warmup

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.filter.OncePerRequestFilter

/** 예열 자격이 인가된 요청의 principal이다. 이름은 음수 회원 ID라 실제 회원과 겹치지 않는다. */
internal class StartupWarmupAuthentication :
    AbstractAuthenticationToken(listOf(SimpleGrantedAuthority("ROLE_USER"), SimpleGrantedAuthority(STARTUP_WARMUP_ROLE))) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Any = "-1"
}

/**
 * 예열 자격 헤더가 있는 요청만 이 체인으로 보낸다. 헤더가 없는 운영 요청은 기존 체인을 그대로 탄다.
 * 서버 역할(STARTUP_WARMUP) JWT와 기동 시 비밀을 모두 확인한 뒤에만 USER 권한을 주며, 허용 경로는 예매 생성 하나다.
 */
internal class StartupWarmupFilter(private val credential: StartupWarmupCredential) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val current = SecurityContextHolder.getContext().authentication
        val key = request.getHeader(STARTUP_WARMUP_HEADER)
        val rejection = when {
            current == null || !current.isAuthenticated -> HttpStatus.UNAUTHORIZED

            current.authorities.none {
                it.authority == STARTUP_WARMUP_ROLE
            } || key == null || !credential.matches(key) -> HttpStatus.FORBIDDEN

            else -> credential.enter()
        }
        if (rejection != null) {
            response.status = rejection.value()
            return
        }
        try {
            SecurityContextHolder.setContext(
                SecurityContextHolder.createEmptyContext().also {
                    it.authentication =
                        StartupWarmupAuthentication()
                },
            )
            chain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
            credential.leave()
        }
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty("ticketing.startup-warmup.enabled", havingValue = "true")
internal class StartupWarmupSecurityConfig {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun startupWarmupFilterChain(http: HttpSecurity, credential: StartupWarmupCredential, jwtDecoder: JwtDecoder): SecurityFilterChain {
        http.securityMatcher(RequestMatcher { it.getHeader(STARTUP_WARMUP_HEADER) != null })
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.requestMatchers(HttpMethod.POST, "/api/v1/bookings").authenticated().anyRequest().denyAll() }
            .oauth2ResourceServer {
                it.jwt { jwt ->
                    jwt.decoder(jwtDecoder).jwtAuthenticationConverter(
                        JwtAuthenticationConverter().apply {
                            setJwtGrantedAuthoritiesConverter(
                                JwtGrantedAuthoritiesConverter().apply {
                                    setAuthoritiesClaimName("role")
                                    setAuthorityPrefix("ROLE_")
                                },
                            )
                        },
                    )
                }
            }
            .addFilterAfter(StartupWarmupFilter(credential), BearerTokenAuthenticationFilter::class.java)
        return http.build()
    }
}
