package io.github.ticketing.gateway.route

import io.github.ticketing.gateway.admission.AdmissionTokenFilter
import io.github.ticketing.gateway.auth.GatewayAttributes
import io.github.ticketing.gateway.auth.MEMBER_HEADER
import io.github.ticketing.gateway.ratelimit.RateLimitFilter
import io.github.ticketing.gateway.sse.SseTicketFilter
import io.github.ticketing.gateway.support.writeProblem
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.cloud.gateway.route.builder.routes
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus

private const val DEFAULT_BOOKING_BODY_MAX_BYTES = 16384

@ConfigurationProperties("ticketing.gateway")
data class GatewayProperties(
    val coreUri: String = "http://localhost:18080",
    val realtimeUri: String = "http://localhost:18081",
    val bookingBodyMaxBytes: Int = DEFAULT_BOOKING_BODY_MAX_BYTES,
)

private const val QUEUE_ROUTE_ORDER = -2

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties::class)
class GatewayRoutes {
    @Bean
    fun routes(
        builder: RouteLocatorBuilder,
        properties: GatewayProperties,
        admissionTokenFilter: AdmissionTokenFilter,
        sseTicketFilter: SseTicketFilter,
        rateLimitFilter: RateLimitFilter,
        @Value("\${ticketing.startup-warmup.enabled:false}") startupWarmup: Boolean,
    ): RouteLocator {
        val requireMember = GatewayFilter { exchange, chain ->
            val memberId = exchange.getAttribute<Long>(GatewayAttributes.MEMBER_ID)
            if (memberId == null) {
                exchange.writeProblem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.")
            } else {
                val request = exchange.request.mutate().header(MEMBER_HEADER, memberId.toString()).build()
                chain.filter(exchange.mutate().request(request).build())
            }
        }
        val requireBookingMember = GatewayFilter { exchange, chain ->
            if (exchange.getAttribute<Long>(GatewayAttributes.MEMBER_ID) == null) {
                exchange.writeProblem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.")
            } else {
                chain.filter(exchange)
            }
        }
        return builder.routes {
            if (startupWarmup) {
                route(id = "core-startup-warmup", order = -1, uri = properties.coreUri) {
                    path("/internal/startup-warmup").and().method(org.springframework.http.HttpMethod.POST)
                        .filters { filters ->
                            filters.filter(GatewayFilter { exchange, chain -> admissionTokenFilter.validateBody(exchange, chain, 1L) })
                        }
                }
            }
            route(id = "realtime-queues", order = QUEUE_ROUTE_ORDER, uri = properties.realtimeUri) {
                path("/api/v1/queues/**").filters { filters -> filters.filter(requireMember).filter(rateLimitFilter) }
            }
            route(id = "realtime-streams", order = -1, uri = properties.realtimeUri) {
                path("/api/v1/streams/**").filters { filters -> filters.filter(sseTicketFilter).filter(rateLimitFilter) }
            }
            route(id = "core-bookings-create", order = -1, uri = properties.coreUri) {
                path("/api/v1/bookings").and().method(org.springframework.http.HttpMethod.POST)
                    .filters { filters -> filters.filter(requireBookingMember).filter(admissionTokenFilter).filter(rateLimitFilter) }
            }
            route(id = "core-api", order = 0, uri = properties.coreUri) {
                path("/api/v1/**").filters { filters -> filters.filter(rateLimitFilter) }
            }
        }
    }
}
