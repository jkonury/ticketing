package io.github.ticketing.payment.adapter.required.pg

import io.github.ticketing.payment.application.port.required.PgPaymentRequest
import io.github.ticketing.payment.application.port.required.PgPort
import io.github.ticketing.payment.application.port.required.PgRequestResult
import io.github.ticketing.payment.application.port.required.PgTransaction
import io.github.ticketing.payment.application.port.required.PgTransactionQueryPort
import io.github.ticketing.payment.application.port.required.PgTransactionStatus
import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.time.Instant
import java.time.LocalDate

class FakePgAdapter(private val client: RestClient) :
    PgPort,
    PgTransactionQueryPort {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun findTransactions(date: LocalDate): List<PgTransaction> {
        val response = client.get().uri { it.path("/v1/transactions").queryParam("date", date).build() }
            .retrieve().body(object : ParameterizedTypeReference<List<TransactionWireResponse>>() {})
        return requireNotNull(response).map {
            PgTransaction(
                it.transactionId,
                it.orderId.toLong(),
                it.amount.toBigDecimal(),
                it.status,
                it.requestedAt,
                it.approvedAt,
                it.refundedAt,
            )
        }
    }

    private data class TransactionWireResponse(
        val transactionId: String,
        val orderId: String,
        val amount: Long,
        val status: PgTransactionStatus,
        val requestedAt: Instant,
        val approvedAt: Instant?,
        val refundedAt: Instant?,
    )

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
        if (body != null && response.statusCode == HttpStatus.ACCEPTED && isValid(body, expectedStatus, expectedTransactionId)) {
            PgRequestResult.Accepted(requireNotNull(body.transactionId))
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
        log.warn("PG 호출에 실패했다", exception)
        PgRequestResult.Unknown("CLIENT_ERROR")
    }

    private fun isValid(body: PgWireResponse, expectedStatus: String?, expectedTransactionId: String?): Boolean {
        val wellFormed = !body.transactionId.isNullOrBlank() && body.status in KNOWN_STATUSES
        val matchesExpected = (expectedStatus == null || body.status == expectedStatus) &&
            (expectedTransactionId == null || body.transactionId == expectedTransactionId)
        return wellFormed && matchesExpected
    }

    private data class PaymentWireRequest(val orderId: String, val amount: Long)
    private data class PgWireResponse(val transactionId: String? = null, val status: String? = null)

    private companion object {
        val KNOWN_STATUSES = setOf("PENDING", "APPROVED", "FAILED", "REFUNDED")
    }
}
