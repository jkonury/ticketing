package io.github.ticketing.booking.adapter.provided.event

import io.github.ticketing.booking.application.port.provided.HandlePaymentResultUseCase
import io.github.ticketing.payment.PaymentFailed
import io.github.ticketing.payment.PaymentRefunded
import io.github.ticketing.payment.PaymentSucceeded
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * 결제 결과를 유스케이스에 넘기기만 한다. 같은 이벤트가 다시 와도 유스케이스가 멱등이다.
 * 낙관적 락 충돌은 삼키지 않고 던져 컨슈머 오류 처리(재시도·DLT)가 다시 시도하게 한다.
 */
@Component
class PaymentResultListener(private val handlePaymentResult: HandlePaymentResultUseCase) {
    @KafkaListener(topics = ["#{@kafkaTopics.paymentSucceeded}"], groupId = "ticketing-booking")
    fun on(event: PaymentSucceeded) {
        handlePaymentResult.onPaymentSucceeded(event.paymentId, event.orderId, event.amount, event.paidAt)
    }

    @KafkaListener(topics = ["#{@kafkaTopics.paymentFailed}"], groupId = "ticketing-booking")
    fun on(event: PaymentFailed) {
        handlePaymentResult.onPaymentFailed(event.paymentId, event.orderId, event.reason)
    }

    @KafkaListener(topics = ["#{@kafkaTopics.paymentRefunded}"], groupId = "ticketing-booking")
    fun on(event: PaymentRefunded) {
        handlePaymentResult.onPaymentRefunded(event.paymentId, event.orderId)
    }
}
