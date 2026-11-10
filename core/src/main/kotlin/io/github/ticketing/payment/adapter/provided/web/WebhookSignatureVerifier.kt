package io.github.ticketing.payment.adapter.provided.web

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * fake-pg 서명: hex(HMAC-SHA256(secret, "{timestamp}.{rawBody}")).
 * 파싱 후 재직렬화하면 바이트가 달라지므로 받은 원문 바이트로 계산하고, 타이밍 공격을 막으려 상수 시간으로 비교한다.
 */
@Component
@EnableConfigurationProperties(FakePgWebhookProperties::class)
class WebhookSignatureVerifier(private val properties: FakePgWebhookProperties, private val clock: Clock) {
    private val key = SecretKeySpec(properties.webhookSecret.toByteArray(StandardCharsets.UTF_8), HMAC_SHA256)

    fun verify(timestamp: String?, signature: String?, rawBody: ByteArray) {
        if (timestamp == null || signature == null) throw InvalidWebhookSignatureException()
        val sentAt = timestamp.toLongOrNull()?.let { runCatching { Instant.ofEpochSecond(it) }.getOrNull() }
            ?: throw InvalidWebhookSignatureException()
        // 오래된 요청을 그대로 다시 보내는 재전송 공격을 막는다
        if (Duration.between(sentAt, clock.instant()).abs() > properties.webhookTolerance) throw InvalidWebhookSignatureException()
        val provided = runCatching { HexFormat.of().parseHex(signature) }.getOrNull() ?: throw InvalidWebhookSignatureException()
        if (!MessageDigest.isEqual(sign(timestamp, rawBody), provided)) throw InvalidWebhookSignatureException()
    }

    private fun sign(timestamp: String, rawBody: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(key)
        mac.update("$timestamp.".toByteArray(StandardCharsets.UTF_8))
        return mac.doFinal(rawBody)
    }

    companion object {
        const val TIMESTAMP_HEADER = "X-FakePg-Timestamp"
        const val SIGNATURE_HEADER = "X-FakePg-Signature"
        private const val HMAC_SHA256 = "HmacSHA256"
    }
}
