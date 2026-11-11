package io.github.ticketing.fakepg.payment.service

import io.github.ticketing.fakepg.payment.config.FakePgProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class WebhookEventType { PAYMENT_APPROVED, PAYMENT_FAILED, REFUND_COMPLETED }

data class WebhookEvent(
    val eventId: String,
    val type: WebhookEventType,
    val transactionId: String,
    val orderId: String,
    val amount: Long,
    val occurredAt: Instant,
    val failureReason: String?,
)

/**
 * 웹훅을 서명해 발송한다. 실패(non-2xx, 연결 오류)하면 max-attempts까지 스케줄러로 재시도한다.
 * 요청 스레드나 작업 스레드에서 sleep으로 기다리지 않는다.
 */
@Component
class WebhookSender(
    private val properties: FakePgProperties,
    private val clock: Clock,
    private val jsonMapper: JsonMapper,
    private val httpClient: HttpClient,
    private val scheduler: TaskScheduler,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun newEvent(type: WebhookEventType, tx: Transaction, failureReason: String? = null) = WebhookEvent(
        eventId = UUID.randomUUID().toString(),
        type = type,
        transactionId = tx.transactionId,
        orderId = tx.orderId,
        amount = tx.amount,
        occurredAt = clock.instant(),
        failureReason = failureReason,
    )

    /** [delay] 뒤에 [event]를 [copies]번 보낸다. 모두 같은 eventId와 같은 본문이다. */
    fun sendAfter(delay: Duration, event: WebhookEvent, copies: Int = 1) {
        val rawBody = jsonMapper.writeValueAsString(event)
        repeat(copies) {
            scheduleAfter(delay) { deliver(event, rawBody, attempt = 1) }
        }
    }

    fun scheduleAfter(delay: Duration, task: () -> Unit) {
        scheduler.schedule(task, scheduler.clock.instant().plus(delay))
    }

    private fun deliver(event: WebhookEvent, rawBody: String, attempt: Int) {
        val delivered = try {
            post(rawBody) in 200..299
        } catch (e: IOException) {
            log.warn("웹훅 연결 실패 transactionId={} eventId={} attempt={}: {}", event.transactionId, event.eventId, attempt, e.message)
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }

        if (delivered) {
            log.info("웹훅 발송 완료 transactionId={} eventId={} type={} attempt={}", event.transactionId, event.eventId, event.type, attempt)
        } else if (attempt < properties.webhook.maxAttempts) {
            scheduleAfter(properties.webhook.retryDelay) { deliver(event, rawBody, attempt + 1) }
        } else {
            log.warn("웹훅 발송 포기 transactionId={} eventId={} attempts={}", event.transactionId, event.eventId, attempt)
        }
    }

    private fun post(rawBody: String): Int {
        val timestamp = clock.instant().epochSecond.toString()
        val request = HttpRequest.newBuilder(URI.create(properties.webhook.url))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header(TIMESTAMP_HEADER, timestamp)
            .header(SIGNATURE_HEADER, sign(properties.webhook.secret, timestamp, rawBody))
            .POST(HttpRequest.BodyPublishers.ofString(rawBody))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    companion object {
        const val TIMESTAMP_HEADER = "X-FakePg-Timestamp"
        const val SIGNATURE_HEADER = "X-FakePg-Signature"

        /** hex(HMAC-SHA256(secret, "{timestamp}.{rawBody}")) */
        fun sign(secret: String, timestamp: String, rawBody: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            return HexFormat.of().formatHex(mac.doFinal("$timestamp.$rawBody".toByteArray(StandardCharsets.UTF_8)))
        }
    }
}
