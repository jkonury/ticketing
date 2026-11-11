package io.github.ticketing.payment.adapter.provided.web

import io.github.ticketing.payment.application.port.provided.HandlePaymentWebhookUseCase
import io.github.ticketing.payment.application.port.provided.PaymentWebhookCommand
import io.github.ticketing.payment.application.port.provided.PaymentWebhookType
import io.github.ticketing.payment.domain.PaymentRuleException
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant

@RestController
class PaymentWebhookController(
    private val verifier: PaymentWebhookSignatureVerifier,
    private val json: JsonMapper,
    private val webhooks: HandlePaymentWebhookUseCase,
) {

    @PostMapping("/api/v1/payments/webhooks/fake-pg")
    fun receive(
        @RequestHeader("X-FakePg-Timestamp", required = false) timestamp: String?,
        @RequestHeader("X-FakePg-Signature", required = false) signature: String?,
        @RequestBody(required = false) body: ByteArray?,
    ) {
        val rawBody = body ?: byteArrayOf()
        verifier.verify(timestamp, signature, rawBody)

        val command = try {
            parse(rawBody)
        } catch (e: Exception) {
            throw InvalidPaymentWebhookException().also { it.initCause(e) }
        }

        try {
            webhooks.handle(command)
        } catch (e: PaymentRuleException) {
            throw PaymentWebhookRuleException(e.code).also { it.initCause(e) }
        }
    }

    // 서명은 통과했지만 본문 형식이 맞지 않으면 입력 오류로 돌린다
    private fun parse(rawBody: ByteArray): PaymentWebhookCommand {
        val node = json.readTree(rawBody)
        require(node.isObject)

        fun requiredText(name: String, max: Int): String {
            val value = node.get(name)
            require(value != null && value.isTextual)
            return value.asString().also { require(it.isNotBlank() && it.length <= max) }
        }

        val eventId = requiredText("eventId", 64)
        val type = PaymentWebhookType.valueOf(requiredText("type", 32))
        val transactionId = requiredText("transactionId", 100)
        val orderId = requiredText("orderId", 19).also { require(it.matches(Regex("[0-9]+"))) }.toLong().also { require(it > 0) }

        val amountNode: JsonNode = requireNotNull(node.get("amount"))
        require(amountNode.isIntegralNumber)
        val amount = amountNode.bigIntegerValue().also { require(it.signum() > 0 && it.toString().length <= 12) }

        val occurredAt = Instant.parse(requiredText("occurredAt", 100))

        val reasonNode = node.get("failureReason")
        require(reasonNode == null || reasonNode.isNull || reasonNode.isTextual)
        val reason = if (reasonNode == null || reasonNode.isNull) null else reasonNode.asString()
        require(type != PaymentWebhookType.PAYMENT_FAILED || !reason.isNullOrBlank())

        return PaymentWebhookCommand(eventId, type, transactionId, orderId, BigDecimal(amount), occurredAt, reason)
    }
}
