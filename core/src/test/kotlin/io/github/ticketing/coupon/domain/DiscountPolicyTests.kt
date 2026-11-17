package io.github.ticketing.coupon.domain

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals

class DiscountPolicyTests {

    private val validFrom = Instant.parse("2026-11-01T00:00:00Z")
    private val validUntil = Instant.parse("2026-12-01T00:00:00Z")
    private val now = Instant.parse("2026-11-10T10:00:00Z")
    private val performance = PerformanceId(7)

    private fun policy(
        type: DiscountType = DiscountType.RATE,
        value: Long = 10,
        maxDiscount: Long? = null,
        minOrderAmount: Long = 0,
        performanceId: PerformanceId? = null,
    ) = DiscountPolicy(
        type,
        BigDecimal(value),
        maxDiscount?.let(::BigDecimal),
        BigDecimal(minOrderAmount),
        performanceId,
        validFrom,
        validUntil,
    )

    private fun accepted(amount: Long) = DiscountResult.Accepted(BigDecimal(amount))

    private fun DiscountResult.amountOrNull() = (this as? DiscountResult.Accepted)?.discountAmount?.toLong()

    @Test
    fun `정액 할인은 값 그대로 적용한다`() {
        val result = policy(DiscountType.FIXED, 3_000).calculate(BigDecimal(10_000), performance, now)

        assertEquals(3_000L, result.amountOrNull())
    }

    @Test
    fun `정률 할인은 원 단위로 내림한다`() {
        val result = policy(value = 15).calculate(BigDecimal(10_001), performance, now)

        assertEquals(1_500L, result.amountOrNull())
    }

    @Test
    fun `정률 할인은 최대 할인 금액을 넘지 않는다`() {
        val result = policy(value = 50, maxDiscount = 2_000).calculate(BigDecimal(10_000), performance, now)

        assertEquals(2_000L, result.amountOrNull())
    }

    @Test
    fun `최소 결제 금액 미만이면 거부하고 정확히 같으면 허용한다`() {
        val rejected = policy(minOrderAmount = 10_000).calculate(BigDecimal(9_999), performance, now)
        val allowed = policy(minOrderAmount = 10_000).calculate(BigDecimal(10_000), performance, now)

        assertEquals(DiscountResult.Rejected(RejectReason.BELOW_MIN_ORDER_AMOUNT), rejected)
        assertEquals(accepted(1_000), allowed)
    }

    @Test
    fun `할인 후 금액이 1원 미만이면 거부하고 1원이 남으면 허용한다`() {
        val rejected = policy(DiscountType.FIXED, 10_000).calculate(BigDecimal(10_000), performance, now)
        val allowed = policy(DiscountType.FIXED, 9_999).calculate(BigDecimal(10_000), performance, now)

        assertEquals(DiscountResult.Rejected(RejectReason.AMOUNT_BELOW_MINIMUM), rejected)
        assertEquals(accepted(9_999), allowed)
    }

    @Test
    fun `대상 공연이 다르면 거부한다`() {
        val result = policy(performanceId = PerformanceId(8)).calculate(BigDecimal(10_000), performance, now)

        assertEquals(DiscountResult.Rejected(RejectReason.PERFORMANCE_MISMATCH), result)
    }

    @Test
    fun `대상 공연이 같으면 허용한다`() {
        val result = policy(performanceId = performance).calculate(BigDecimal(10_000), performance, now)

        assertEquals(accepted(1_000), result)
    }

    @Test
    fun `유효 기간은 시작 시각을 포함하고 종료 시각은 제외한다`() {
        val atStart = policy().calculate(BigDecimal(10_000), performance, validFrom)
        val beforeStart = policy().calculate(BigDecimal(10_000), performance, validFrom.minusSeconds(1))
        val atEnd = policy().calculate(BigDecimal(10_000), performance, validUntil)

        assertEquals(accepted(1_000), atStart)
        assertEquals(DiscountResult.Rejected(RejectReason.OUT_OF_VALID_PERIOD), beforeStart)
        assertEquals(DiscountResult.Rejected(RejectReason.OUT_OF_VALID_PERIOD), atEnd)
    }
}
