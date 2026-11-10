package io.github.ticketing.fakepg.payment.web

import io.github.ticketing.fakepg.payment.config.FakePgProperties
import io.github.ticketing.fakepg.payment.service.PaymentService
import io.github.ticketing.fakepg.payment.service.Scenario
import io.github.ticketing.fakepg.payment.service.TransactionStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/v1")
class PaymentController(
    private val paymentService: PaymentService,
    private val properties: FakePgProperties,
) {

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestPayment(
        @RequestHeader("Idempotency-Key") @NotBlank idempotencyKey: String,
        @Valid @RequestBody request: PaymentRequest,
    ): PaymentAcceptedResponse {
        val registration = paymentService.requestPayment(idempotencyKey, request.toCommand())
        val tx = registration.transaction
        if (registration.created && tx.scenario == Scenario.TIMEOUT) {
            // 클라이언트 타임아웃을 유도하려고 응답만 늦춘다. Virtual Thread 위라 플랫폼 스레드를 점유하지 않는다
            Thread.sleep(properties.behavior.timeoutDelay)
        }
        return PaymentAcceptedResponse(tx.transactionId, TransactionStatus.PENDING)
    }

    @PostMapping("/payments/{transactionId}/refund")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun refund(
        @PathVariable transactionId: String,
        @RequestHeader("Idempotency-Key") @NotBlank idempotencyKey: String,
    ): RefundAcceptedResponse {
        val result = paymentService.refund(transactionId, idempotencyKey)
        return RefundAcceptedResponse(result.transaction.transactionId, result.transaction.status)
    }

    @GetMapping("/transactions")
    fun transactions(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): List<TransactionResponse> =
        paymentService.findTransactions(date).map(TransactionResponse::from)
}
