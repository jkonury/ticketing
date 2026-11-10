package io.github.ticketing.payment.adapter.required.pg

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.github.ticketing.payment.application.port.required.PgPaymentRequest
import io.github.ticketing.payment.application.port.required.PgPort
import io.github.ticketing.payment.application.port.required.PgRequestResult
import org.springframework.http.MediaType
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

/**
 * fake-pg HTTP 어댑터. 4xx만 확정적 실패(Rejected)로 본다.
 * 타임아웃·5xx·연결 실패는 PG에서 처리됐을 수 있으므로 Unknown이다.
 */
class FakePgAdapter(private val restClient: RestClient) : PgPort {
    override fun requestPayment(request: PgPaymentRequest): PgRequestResult = call {
        restClient.post()
            .uri("/v1/payments")
            .header(IDEMPOTENCY_KEY, request.idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            // fake-pg는 원 단위 정수 금액을 받는다
            .body(PaymentBody(request.orderId, request.amount.longValueExact()))
            .retrieve()
            .body(AcceptedBody::class.java)
    }

    override fun requestRefund(transactionId: String, idempotencyKey: String): PgRequestResult = call {
        restClient.post()
            .uri("/v1/payments/{transactionId}/refund", transactionId)
            .header(IDEMPOTENCY_KEY, idempotencyKey)
            .retrieve()
            .body(AcceptedBody::class.java)
    }

    private inline fun call(block: () -> AcceptedBody?): PgRequestResult = try {
        val transactionId = block()?.transactionId
        if (transactionId.isNullOrBlank()) PgRequestResult.Unknown("응답에 transactionId가 없다") else PgRequestResult.Accepted(transactionId)
    } catch (e: HttpClientErrorException) {
        PgRequestResult.Rejected(errorCode(e))
    } catch (e: HttpServerErrorException) {
        PgRequestResult.Unknown("HTTP ${e.statusCode.value()}")
    } catch (e: ResourceAccessException) {
        // 연결 실패와 읽기 타임아웃. 원인 예외 이름만 남긴다
        PgRequestResult.Unknown((e.rootCause ?: e).javaClass.simpleName)
    } catch (e: RestClientException) {
        PgRequestResult.Unknown(e.javaClass.simpleName)
    }

    private fun errorCode(e: RestClientResponseException): String =
        runCatching { e.getResponseBodyAs(ErrorBody::class.java)?.code }.getOrNull() ?: "HTTP_${e.statusCode.value()}"

    internal data class PaymentBody(val orderId: String, val amount: Long)

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class AcceptedBody(val transactionId: String?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class ErrorBody(val code: String?)

    companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
    }
}
