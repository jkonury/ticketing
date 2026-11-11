package io.github.ticketing.payment.adapter.required.pg

import io.github.ticketing.payment.application.port.required.PgPaymentRequest
import io.github.ticketing.payment.application.port.required.PgPort
import io.github.ticketing.payment.application.port.required.PgRequestResult
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

class FakePgAdapter(private val client: RestClient) : PgPort {
    override fun requestPayment(request: PgPaymentRequest): PgRequestResult {
        val amount = request.amount.longValueExact()
        return call(expectedStatus = "PENDING") {
            client.post().uri("/v1/payments")
                .header("Idempotency-Key", request.idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(PaymentWireRequest(request.orderId, amount))
                .retrieve().toEntity(PgWireResponse::class.java)
        }
    }

    override fun requestRefund(transactionId: String, idempotencyKey: String): PgRequestResult =
        call(expectedTransactionId = transactionId) {
            client.post().uri("/v1/payments/{transactionId}/refund", transactionId)
                .header("Idempotency-Key", idempotencyKey)
                .retrieve().toEntity(PgWireResponse::class.java)
        }

    private fun call(
        expectedStatus: String? = null,
        expectedTransactionId: String? = null,
        request: () -> org.springframework.http.ResponseEntity<PgWireResponse>,
    ): PgRequestResult = try {
        val response = request()
        val body = response.body
        if (response.statusCode == HttpStatus.ACCEPTED && body != null &&
            !body.transactionId.isNullOrBlank() &&
            body.status in setOf("PENDING", "APPROVED", "FAILED", "REFUNDED") &&
            (expectedStatus == null || body.status == expectedStatus) &&
            (expectedTransactionId == null || body.transactionId == expectedTransactionId)
        ) {
            PgRequestResult.Accepted(body.transactionId)
        } else {
            PgRequestResult.Unknown("INVALID_RESPONSE")
        }
    } catch (exception: RestClientResponseException) {
        if (exception.statusCode.is4xxClientError) {
            PgRequestResult.Rejected("HTTP_4XX")
        } else {
            PgRequestResult.Unknown("HTTP_ERROR")
        }
    } catch (exception: RestClientException) {
        PgRequestResult.Unknown("CLIENT_ERROR")
    }

    private data class PaymentWireRequest(val orderId: String, val amount: Long)
    private data class PgWireResponse(val transactionId: String? = null, val status: String? = null)
}
