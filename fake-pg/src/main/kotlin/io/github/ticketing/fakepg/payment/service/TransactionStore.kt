package io.github.ticketing.fakepg.payment.service

import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

data class PaymentCommand(val orderId: String, val amount: Long, val scenario: Scenario?)

data class Registration(val transaction: Transaction, val created: Boolean)

data class RefundResult(val transaction: Transaction, val created: Boolean)

/** 메모리 거래 저장소. 재시작하면 내역이 사라진다. */
@Component
class TransactionStore {

    private val transactions = ConcurrentHashMap<String, Transaction>()
    private val paymentKeys = ConcurrentHashMap<String, PaymentKeyEntry>()
    private val refundKeys = ConcurrentHashMap<String, String>()

    private data class PaymentKeyEntry(val command: PaymentCommand, val transactionId: String)

    /**
     * 키가 처음이면 [create]로 거래를 만들어 등록하고, 이미 있으면 기존 거래를 돌려준다.
     * 거래 생성과 키 등록을 computeIfAbsent 안에서 해 동시 재시도에도 거래가 하나만 생긴다.
     */
    fun register(key: String, command: PaymentCommand, create: () -> Transaction): Registration {
        var createdNow: Transaction? = null
        val entry = paymentKeys.computeIfAbsent(key) {
            val tx = create()
            transactions[tx.transactionId] = tx
            createdNow = tx
            PaymentKeyEntry(command, tx.transactionId)
        }
        createdNow?.let { return Registration(it, created = true) }
        if (entry.command != command) throw IdempotencyKeyReusedException(key)
        val existing = transactions[entry.transactionId] ?: throw TransactionNotFoundException(entry.transactionId)
        return Registration(existing, created = false)
    }

    fun approve(transactionId: String, at: Instant): Transaction? =
        transactions.computeIfPresent(transactionId) { _, tx ->
            if (tx.status == TransactionStatus.PENDING) tx.approve(at) else tx
        }

    fun fail(transactionId: String): Transaction? =
        transactions.computeIfPresent(transactionId) { _, tx ->
            if (tx.status == TransactionStatus.PENDING) tx.fail() else tx
        }

    /** 같은 거래에 같은 키로 다시 오면 상태를 바꾸지 않고 현재 거래를 돌려준다. */
    fun refund(transactionId: String, key: String, at: Instant): RefundResult {
        if (!transactions.containsKey(transactionId)) throw TransactionNotFoundException(transactionId)
        var refundedNow: Transaction? = null
        refundKeys.computeIfAbsent("$transactionId:$key") {
            val updated = transactions.computeIfPresent(transactionId) { _, tx ->
                if (tx.status != TransactionStatus.APPROVED) throw TransactionNotRefundableException(transactionId)
                tx.refund(at)
            } ?: throw TransactionNotFoundException(transactionId)
            refundedNow = updated
            transactionId
        }
        refundedNow?.let { return RefundResult(it, created = true) }
        val current = transactions[transactionId] ?: throw TransactionNotFoundException(transactionId)
        return RefundResult(current, created = false)
    }

    fun findByRequestedDate(date: LocalDate): List<Transaction> =
        transactions.values
            .filter { LocalDate.ofInstant(it.requestedAt, ZoneOffset.UTC) == date }
            .sortedWith(compareBy({ it.requestedAt }, { it.transactionId }))
}
