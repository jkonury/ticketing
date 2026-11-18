package io.github.ticketing.payment.adapter.provided.event

import io.github.ticketing.payment.RefundRequested
import io.github.ticketing.payment.application.port.provided.RequestRefundCommand
import io.github.ticketing.payment.application.port.provided.RequestRefundUseCase
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/** 같은 환불 요청이 다시 와도 이미 `REFUNDED`면 건너뛰고, 진행 중이면 같은 PG 멱등 키로 다시 요청한다. */
@Component
class RefundRequestListener(private val refunds: RequestRefundUseCase) {
    @KafkaListener(topics = ["#{@kafkaTopics.refundRequested}"], groupId = "ticketing-payment")
    fun on(event: RefundRequested) = refunds.request(RequestRefundCommand(event.orderId, event.reason, event.requestedAt))
}
