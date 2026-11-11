package io.github.ticketing.fakepg.payment.web

import io.github.ticketing.fakepg.payment.service.IdempotencyKeyReusedException
import io.github.ticketing.fakepg.payment.service.TransactionNotFoundException
import io.github.ticketing.fakepg.payment.service.TransactionNotRefundableException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@RestControllerAdvice
class PaymentExceptionHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(IdempotencyKeyReusedException::class)
    fun keyReused(e: IdempotencyKeyReusedException) = problem(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED", e.message)

    @ExceptionHandler(TransactionNotFoundException::class)
    fun notFound(e: TransactionNotFoundException) = problem(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", e.message)

    @ExceptionHandler(TransactionNotRefundableException::class)
    fun notRefundable(e: TransactionNotRefundableException) = problem(HttpStatus.CONFLICT, "TRANSACTION_NOT_REFUNDABLE", e.message)

    private fun problem(status: HttpStatus, code: String, detail: String?): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail ?: code).apply { setProperty("code", code) }
}
