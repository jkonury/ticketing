package io.github.ticketing.payment.domain

/** 도메인 규칙 위반. 로그와 경보 대상이다. */
open class PaymentRuleException(val code: String, message: String = code) : RuntimeException(message)

class InvalidPaymentStateException(status: PaymentStatus, attempted: String) :
    PaymentRuleException(INVALID_PAYMENT_STATE, "$INVALID_PAYMENT_STATE: $status 에서 $attempted 불가")

class PaymentAmountMismatchException(expected: java.math.BigDecimal, actual: java.math.BigDecimal) :
    PaymentRuleException(PAYMENT_AMOUNT_MISMATCH, "$PAYMENT_AMOUNT_MISMATCH: 요청 $expected, 승인 $actual")

class PgTransactionConflictException(existing: String, incoming: String) :
    PaymentRuleException(PG_TRANSACTION_CONFLICT, "$PG_TRANSACTION_CONFLICT: 기존 $existing, 신규 $incoming")

const val INVALID_PAYMENT_STATE = "INVALID_PAYMENT_STATE"
const val PAYMENT_AMOUNT_MISMATCH = "PAYMENT_AMOUNT_MISMATCH"
const val PG_TRANSACTION_CONFLICT = "PG_TRANSACTION_CONFLICT"
