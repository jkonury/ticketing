package io.github.ticketing

import io.github.ticketing.booking.adapter.provided.event.PaymentResultListener
import io.github.ticketing.booking.application.port.provided.HandlePaymentResultUseCase
import io.github.ticketing.payment.adapter.provided.event.PaymentRequestListener
import io.github.ticketing.payment.adapter.provided.event.RefundRequestListener
import io.github.ticketing.payment.application.port.provided.RequestPaymentCommand
import io.github.ticketing.payment.application.port.provided.RequestPaymentUseCase
import io.github.ticketing.payment.application.port.provided.RequestRefundCommand
import io.github.ticketing.payment.application.port.provided.RequestRefundUseCase
import java.math.BigDecimal

object TestListenerFactories {
    @JvmStatic
    fun paymentRequestListener() = PaymentRequestListener(object : RequestPaymentUseCase {
        override fun request(command: RequestPaymentCommand) = Unit
    })

    @JvmStatic
    fun refundRequestListener() = RefundRequestListener(object : RequestRefundUseCase {
        override fun request(command: RequestRefundCommand) = Unit
    })

    @JvmStatic
    fun bookingListener() = PaymentResultListener(object : HandlePaymentResultUseCase {
        override fun onPaymentSucceeded(paymentId: Long, bookingId: Long, amount: BigDecimal) = Unit
        override fun onPaymentFailed(paymentId: Long, bookingId: Long, reason: String) = Unit
        override fun onPaymentRefunded(paymentId: Long, bookingId: Long) = Unit
    })
}
