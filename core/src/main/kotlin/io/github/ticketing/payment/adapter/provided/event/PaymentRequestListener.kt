package io.github.ticketing.payment.adapter.provided.event

import io.github.ticketing.payment.PaymentRequested
import io.github.ticketing.payment.application.port.provided.RequestPaymentCommand
import io.github.ticketing.payment.application.port.provided.RequestPaymentUseCase
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * 같은 결제 요청이 다시 와도 유스케이스가 멱등이다(주문 ID·멱등 키 유니크, PG 거래가 이미 붙은 결제는 PG를 다시 부르지 않는다).
 * 실패는 던져 컨슈머 오류 처리(재시도·DLT)에 맡긴다.
 */
@Component
class PaymentRequestListener(private val payments: RequestPaymentUseCase) {
    @KafkaListener(topics = ["#{@kafkaTopics.paymentRequested}"], groupId = "ticketing-payment")
    fun on(event: PaymentRequested) =
        payments.request(RequestPaymentCommand(event.orderId, event.memberId, event.amount, event.idempotencyKey, event.requestedAt))
}
