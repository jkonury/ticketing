package io.github.ticketing.realtime.queue

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

private const val DEFAULT_BATCH_SIZE = 50
private const val DEFAULT_TOKEN_TTL_MINUTES = 10L
private const val DEFAULT_PRESENCE_GRACE_SECONDS = 30L
private const val DEFAULT_HEARTBEAT_SECONDS = 15L
private const val DEFAULT_PREPARATION_GRACE_SECONDS = 5L

@ConfigurationProperties("ticketing.realtime.queue")
data class QueueProperties(
    val admissionInterval: Duration = Duration.ofSeconds(1),
    val admissionBatchSize: Int = DEFAULT_BATCH_SIZE,
    val admissionTokenTtl: Duration = Duration.ofMinutes(DEFAULT_TOKEN_TTL_MINUTES),
    val admissionPreparationGrace: Duration = Duration.ofSeconds(DEFAULT_PREPARATION_GRACE_SECONDS),
    val presenceGrace: Duration = Duration.ofSeconds(DEFAULT_PRESENCE_GRACE_SECONDS),
    val positionPollInterval: Duration = Duration.ofSeconds(1),
    val heartbeatInterval: Duration = Duration.ofSeconds(DEFAULT_HEARTBEAT_SECONDS),
)

@Configuration
@EnableConfigurationProperties(QueueProperties::class)
class QueueConfiguration
