package io.github.ticketing.payment.application.port.provided

/** 주문의 결제를 환불한다. 같은 주문으로 여러 번 와도 PG 환불 요청은 한 번 접수된다. */
interface RefundPaymentUseCase {
    fun refund(orderId: Long, reason: String)
}
