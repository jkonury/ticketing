package io.github.ticketing.booking.application.port.provided

interface RequestPaymentUseCase {
    /** 이미 요청한 예매는 이벤트를 다시 발행하지 않고 현재 상태를 돌려준다(멱등). */
    fun request(memberId: Long, bookingId: Long): PaymentRequestResult
}

data class PaymentRequestResult(val bookingId: Long, val status: String)
