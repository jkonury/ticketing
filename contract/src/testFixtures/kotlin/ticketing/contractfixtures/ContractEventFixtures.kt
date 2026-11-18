package ticketing.contractfixtures

import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
import io.github.ticketing.payment.PaymentFailed
import io.github.ticketing.payment.PaymentRefunded
import io.github.ticketing.payment.PaymentRequested
import io.github.ticketing.payment.PaymentSucceeded
import io.github.ticketing.payment.RefundRequested
import java.math.BigDecimal
import java.time.Instant

/** 계약 이벤트 샘플과 골든 JSON(`contract-events` 디렉터리). 직렬화기가 달라도 같은 형식을 검증하는 데 쓴다. */
object ContractEventFixtures {
    private val at = Instant.parse("2026-01-02T03:04:05Z")

    val samples: List<Any> = listOf(
        PaymentRequested(101, 7, BigDecimal(50000), "booking-101-pay", at),
        RefundRequested(101, "USER_CANCELLED", at),
        PaymentSucceeded(11, 101, BigDecimal(50000), at),
        PaymentFailed(11, 101, "CARD_DECLINED", at),
        PaymentRefunded(11, 101, at),
        BookingConfirmed(101, 7, 3, 2, 5, listOf(31, 32), BigDecimal(45000), BigDecimal(50000), BigDecimal(5000), at),
        BookingCancelled(101, 7, 3, 2, 5, "USER_CANCELLED", true, at),
    )

    fun golden(event: Any): String = checkNotNull(javaClass.getResource("/contract-events/${event.javaClass.simpleName}.json")) {
        "골든 파일이 없다: ${event.javaClass.simpleName}.json"
    }.readText()
}
