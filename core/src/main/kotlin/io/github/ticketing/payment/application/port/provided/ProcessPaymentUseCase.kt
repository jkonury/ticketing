package io.github.ticketing.payment.application.port.provided

import java.math.BigDecimal

/** 결제 요청을 받아 PG에 보낸다. 같은 주문으로 다시 와도 PG 거래는 하나다. */
interface ProcessPaymentUseCase {
    fun process(command: ProcessPaymentCommand)
}

data class ProcessPaymentCommand(
    val orderId: Long,
    val memberId: Long,
    val amount: BigDecimal,
    val idempotencyKey: String,
)
