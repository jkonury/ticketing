package io.github.ticketing.payment.adapter.required.pg

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@ConfigurationProperties("ticketing.payment.fake-pg")
class PaymentPgProperties(
    val baseUrl: String = "http://localhost:8090",
    val connectTimeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    val readTimeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    val webhookSecret: String,
    val webhookTimestampTolerance: Duration = Duration.ofMinutes(DEFAULT_WEBHOOK_TOLERANCE_MINUTES),
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentPgProperties::class)
class PaymentPgConfiguration {
    @Bean
    fun fakePgAdapter(builder: RestClient.Builder, properties: PaymentPgProperties): FakePgAdapter {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(properties.connectTimeout)
            setReadTimeout(properties.readTimeout)
        }
        return FakePgAdapter(builder.baseUrl(properties.baseUrl).requestFactory(factory).build())
    }
}

private const val DEFAULT_TIMEOUT_SECONDS = 3L
private const val DEFAULT_WEBHOOK_TOLERANCE_MINUTES = 5L
