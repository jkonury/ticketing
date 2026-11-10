package io.github.ticketing.fakepg.payment.service

import java.time.Instant

enum class TransactionStatus { PENDING, APPROVED, FAILED, REFUNDED }

enum class Scenario { SUCCESS, FAIL, TIMEOUT, DELAYED, DUPLICATE_WEBHOOK }

/** 상태 전이는 새 인스턴스를 돌려준다. 저장소가 원자적으로 교체한다. */
data class Transaction(
    val transactionId: String,
    val orderId: String,
    val amount: Long,
    val scenario: Scenario,
    val status: TransactionStatus,
    val requestedAt: Instant,
    val approvedAt: Instant? = null,
    val refundedAt: Instant? = null,
) {

    fun approve(at: Instant): Transaction {
        check(status == TransactionStatus.PENDING) { "PENDING 거래만 승인할 수 있다: $status" }
        return copy(status = TransactionStatus.APPROVED, approvedAt = at)
    }

    fun fail(): Transaction {
        check(status == TransactionStatus.PENDING) { "PENDING 거래만 실패 처리할 수 있다: $status" }
        return copy(status = TransactionStatus.FAILED)
    }

    fun refund(at: Instant): Transaction {
        check(status == TransactionStatus.APPROVED) { "APPROVED 거래만 환불할 수 있다: $status" }
        return copy(status = TransactionStatus.REFUNDED, refundedAt = at)
    }
}
