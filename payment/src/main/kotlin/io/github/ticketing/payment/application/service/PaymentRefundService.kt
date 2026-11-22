package io.github.ticketing.payment.application.service

import io.github.ticketing.payment.application.port.provided.RequestRefundCommand
import io.github.ticketing.payment.application.port.provided.RequestRefundUseCase
import io.github.ticketing.payment.application.port.required.PgPort
import io.github.ticketing.payment.application.port.required.PgRequestResult
import org.springframework.stereotype.Service

@Service
class PaymentRefundService(private val transactions: PaymentTransactionService, private val pg: PgPort) : RequestRefundUseCase {
    override fun request(command: RequestRefundCommand) {
        val payment = transactions.prepareRefund(command) ?: return
        val transactionId = checkNotNull(payment.pgTransactionId) { "환불할 PG 거래 ID가 없습니다" }
        when (val result = pg.requestRefund(transactionId, "refund-${payment.orderId.value}")) {
            is PgRequestResult.Accepted -> Unit
            is PgRequestResult.Rejected -> throw PaymentPgException(result.reason)
            is PgRequestResult.Unknown -> throw PaymentPgException(result.cause)
        }
    }
}
