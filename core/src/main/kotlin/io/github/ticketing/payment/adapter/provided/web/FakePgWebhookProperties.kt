package io.github.ticketing.payment.adapter.provided.web

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** [webhookSecret]은 저장소에 넣지 않고 환경 변수(`FAKE_PG_WEBHOOK_SECRET`)로 받는다. fake-pg와 같은 값이어야 한다. */
@ConfigurationProperties("ticketing.payment.fake-pg")
data class FakePgWebhookProperties(
    val webhookSecret: String,
    val webhookTolerance: Duration = Duration.ofMinutes(5),
) {
    init {
        require(webhookSecret.isNotBlank()) { "ticketing.payment.fake-pg.webhook-secret이 비어 있습니다." }
    }
}
