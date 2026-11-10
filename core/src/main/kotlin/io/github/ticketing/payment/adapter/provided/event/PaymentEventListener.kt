package io.github.ticketing.payment.adapter.provided.event

import io.github.ticketing.payment.PaymentRequested
import io.github.ticketing.payment.RefundRequested
import io.github.ticketing.payment.application.port.provided.ProcessPaymentCommand
import io.github.ticketing.payment.application.port.provided.ProcessPaymentUseCase
import io.github.ticketing.payment.application.port.provided.RefundPaymentUseCase
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation

/**
 * 결제·환불 요청은 PG 호출을 포함하므로 리스너가 트랜잭션을 열지 않는다(기본값 REQUIRES_NEW를 끈다).
 * 트랜잭션 경계는 유스케이스가 PG 호출 앞뒤로 나눠 잡는다. 예외는 삼키지 않아 이벤트 발행 레지스트리의 재처리 대상으로 남는다.
 */
@Component
class PaymentEventListener(
    private val processPayment: ProcessPaymentUseCase,
    private val refundPayment: RefundPaymentUseCase,
) {
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    fun on(event: PaymentRequested) {
        processPayment.process(ProcessPaymentCommand(event.orderId, event.memberId, event.amount, event.idempotencyKey))
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    fun on(event: RefundRequested) {
        refundPayment.refund(event.orderId, event.reason)
    }
}
