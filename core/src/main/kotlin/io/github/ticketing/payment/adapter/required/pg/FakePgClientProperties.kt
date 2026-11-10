package io.github.ticketing.payment.adapter.required.pg

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("ticketing.payment.fake-pg")
data class FakePgClientProperties(
    val baseUrl: String = "http://localhost:8090",
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(3),
)
