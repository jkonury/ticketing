package io.github.ticketing.payment.application.service

import io.github.ticketing.DomainException
import io.github.ticketing.payment.domain.PaymentRuleException
import org.springframework.http.HttpStatus

/** PG가 재시도하도록 404로 응답한다. 결제 행이 아직 없거나 다른 주문의 거래일 수 있다. */
class PgTransactionNotFoundException :
    DomainException("PAYMENT_NOT_FOUND", HttpStatus.NOT_FOUND, "웹훅의 거래에 해당하는 결제를 찾을 수 없습니다.")

/** PG 결과가 확정되지 않아 이벤트 발행 레지스트리가 다시 처리하게 던진다. */
class PgResultUnknownException(operation: String, orderId: Long, cause: String) :
    RuntimeException("PG $operation 결과 미확정 orderId=$orderId cause=$cause")

internal class PaymentRuleViolation(code: String, message: String) : DomainException(code, HttpStatus.CONFLICT, message)

internal inline fun <T> translatingPaymentRules(block: () -> T): T =
    try {
        block()
    } catch (e: PaymentRuleException) {
        throw PaymentRuleViolation(e.code, e.message ?: e.code)
    }
