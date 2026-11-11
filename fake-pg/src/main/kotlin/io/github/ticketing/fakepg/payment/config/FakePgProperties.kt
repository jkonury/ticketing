package io.github.ticketing.fakepg.payment.config

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("fake-pg")
class FakePgProperties(@field:Valid val webhook: Webhook, @field:Valid val behavior: Behavior = Behavior()) {

    // secret이 노출되지 않도록 data class로 만들지 않는다(toString 방지)
    class Webhook(
        @field:NotBlank val url: String,
        @field:NotBlank val secret: String,
        @field:NotNull val delay: Duration = Duration.ofMillis(500),
        @field:Min(0) val duplicateCount: Int = 2,
        @field:Min(1) val maxAttempts: Int = 3,
        // 수신 실패 후 다음 시도까지의 간격
        @field:NotNull val retryDelay: Duration = Duration.ofSeconds(1),
    )

    class Behavior(
        @field:DecimalMin("0.0") @field:DecimalMax("1.0") val failureRate: Double = 0.0,
        @field:DecimalMin("0.0") @field:DecimalMax("1.0") val timeoutRate: Double = 0.0,
        @field:DecimalMin("0.0") @field:DecimalMax("1.0") val duplicateRate: Double = 0.0,
        @field:NotNull val timeoutDelay: Duration = Duration.ofSeconds(10),
        @field:NotNull val delayedWebhookDelay: Duration = Duration.ofMinutes(6),
    )
}
