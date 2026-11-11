package io.github.ticketing.member.service

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

private const val DEFAULT_TTL_SECONDS = 30L

@ConfigurationProperties("ticketing.sse-ticket")
data class SseTicketProperties(val ttl: Duration = Duration.ofSeconds(DEFAULT_TTL_SECONDS)) {
    init {
        require(!ttl.isNegative && !ttl.isZero)
    }
}

data class SseTicket(val ticket: String, val expiresAt: Instant)

@Service
@EnableConfigurationProperties(SseTicketProperties::class)
class SseTicketService(private val redis: StringRedisTemplate, private val clock: Clock, private val properties: SseTicketProperties) {
    private val random = SecureRandom()

    fun issue(memberId: Long): SseTicket {
        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val expiresAt = clock.instant().plus(properties.ttl)
        redis.opsForValue().set("sse:ticket:$ticket", memberId.toString(), properties.ttl)
        return SseTicket(ticket, expiresAt)
    }
}
