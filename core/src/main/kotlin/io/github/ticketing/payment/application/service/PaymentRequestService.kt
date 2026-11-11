package io.github.ticketing.payment.application.service

import io.github.ticketing.payment.application.port.provided.RequestPaymentCommand
import io.github.ticketing.payment.application.port.provided.RequestPaymentUseCase
import io.github.ticketing.payment.application.port.required.PgPaymentRequest
import io.github.ticketing.payment.application.port.required.PgPort
import io.github.ticketing.payment.application.port.required.PgRequestResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

class PaymentPgException(val classification: String) : RuntimeException("PG 요청 실패: $classification")

@Service
class PaymentRequestService(private val transactions: PaymentTransactionService, private val pg: PgPort) : RequestPaymentUseCase {
    private val log = LoggerFactory.getLogger(javaClass)
    override fun request(command: RequestPaymentCommand) {
        val payment = transactions.preparePayment(command) ?: return
        when (val result = pg.requestPayment(PgPaymentRequest(payment.orderId.value.toString(), payment.amount, payment.idempotencyKey))) {
            is PgRequestResult.Accepted -> transactions.acceptPayment(payment.orderId.value, result.transactionId)

            is PgRequestResult.Rejected -> transactions.rejectPayment(payment.orderId.value, result.reason)

            is PgRequestResult.Unknown -> {
                log.warn("결제 결과 미확정: orderId={}, paymentId={}, classification={}", payment.orderId.value, payment.id?.value, result.cause)
                throw PaymentPgException(result.cause)
            }
        }
    }
}
