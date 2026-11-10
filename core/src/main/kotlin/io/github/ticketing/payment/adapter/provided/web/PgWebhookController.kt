package io.github.ticketing.payment.adapter.provided.web

import io.github.ticketing.payment.application.port.provided.HandlePgWebhookUseCase
import io.github.ticketing.payment.application.port.provided.PgWebhookCommand
import io.github.ticketing.payment.application.port.provided.PgWebhookType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant

/** 인증 대신 서명으로 보호하는 공개 경로다. 서명 값과 본문은 로그에 남기지 않는다. */
@RestController
@RequestMapping("/api/v1/payments/webhooks")
class PgWebhookController(
    private val verifier: WebhookSignatureVerifier,
    private val handleWebhook: HandlePgWebhookUseCase,
    private val jsonMapper: JsonMapper,
) {
    @PostMapping("/fake-pg")
    fun fakePg(
        @RequestHeader(WebhookSignatureVerifier.TIMESTAMP_HEADER, required = false) timestamp: String?,
        @RequestHeader(WebhookSignatureVerifier.SIGNATURE_HEADER, required = false) signature: String?,
        // 서명은 원문 바이트로 검증해야 하므로 DTO로 바로 받지 않는다
        @RequestBody rawBody: ByteArray,
    ): ResponseEntity<Void> {
        verifier.verify(timestamp, signature, rawBody)
        val request = try {
            jsonMapper.readValue(rawBody, FakePgWebhookRequest::class.java)
        } catch (e: JacksonException) {
            throw InvalidWebhookPayloadException()
        }
        handleWebhook.handle(request.toCommand())
        return ResponseEntity.ok().build()
    }
}

data class FakePgWebhookRequest(
    val eventId: String,
    val type: PgWebhookType,
    val transactionId: String,
    val orderId: String,
    val amount: BigDecimal,
    val occurredAt: Instant?,
    val failureReason: String?,
) {
    fun toCommand() = PgWebhookCommand(eventId, type, transactionId, orderId.toLongOrNull(), amount, failureReason)
}
