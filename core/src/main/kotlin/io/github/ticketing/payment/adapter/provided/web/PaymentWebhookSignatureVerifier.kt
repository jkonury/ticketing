package io.github.ticketing.payment.adapter.provided.web

import io.github.ticketing.DomainException
import io.github.ticketing.payment.adapter.required.pg.PaymentPgProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class InvalidWebhookSignatureException : DomainException("INVALID_WEBHOOK_SIGNATURE", HttpStatus.UNAUTHORIZED, "웹훅 서명이 올바르지 않습니다.")
class InvalidPaymentWebhookException : DomainException("INVALID_PAYMENT_WEBHOOK", HttpStatus.BAD_REQUEST, "웹훅 입력이 올바르지 않습니다.")
class PaymentWebhookRuleException(code: String) :
    DomainException(
        code,
        if (code ==
            "PAYMENT_NOT_FOUND"
        ) {
            HttpStatus.NOT_FOUND
        } else {
            HttpStatus.CONFLICT
        },
        code,
    )

@Component
class PaymentWebhookSignatureVerifier(private val properties: PaymentPgProperties, private val clock: Clock) {
    fun verify(timestamp: String?, signature: String?, body: ByteArray) {
        try {
            require(timestamp != null && timestamp.matches(Regex("[0-9]+")))
            require(signature != null && signature.matches(Regex("[0-9a-fA-F]{64}")))
            val time = Instant.ofEpochSecond(timestamp.toLong())
            val distance = Duration.between(time, clock.instant()).abs()
            require(distance <= properties.webhookTimestampTolerance)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(properties.webhookSecret.toByteArray(UTF_8), "HmacSHA256"))
            mac.update(timestamp.toByteArray(UTF_8))
            mac.update('.'.code.toByte())
            require(MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(signature)))
        } catch (e: IllegalArgumentException) {
            throw InvalidWebhookSignatureException().also { it.initCause(e) }
        } catch (e: ArithmeticException) {
            throw InvalidWebhookSignatureException().also { it.initCause(e) }
        } catch (e: java.time.DateTimeException) {
            throw InvalidWebhookSignatureException().also { it.initCause(e) }
        }
    }
}
