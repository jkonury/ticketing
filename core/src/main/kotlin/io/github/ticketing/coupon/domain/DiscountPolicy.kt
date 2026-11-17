package io.github.ticketing.coupon.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

enum class DiscountType { FIXED, RATE }

enum class RejectReason {
    OUT_OF_VALID_PERIOD,
    PERFORMANCE_MISMATCH,
    BELOW_MIN_ORDER_AMOUNT,
    AMOUNT_BELOW_MINIMUM,
}

sealed interface DiscountResult {
    data class Accepted(val discountAmount: BigDecimal) : DiscountResult

    data class Rejected(val reason: RejectReason) : DiscountResult
}

/** 할인 조건과 계산. RATE의 값은 퍼센트(10 = 10%)이며 원 단위 내림한다. */
data class DiscountPolicy(
    val type: DiscountType,
    val value: BigDecimal,
    val maxDiscount: BigDecimal?,
    val minOrderAmount: BigDecimal,
    val performanceId: PerformanceId?,
    val validFrom: Instant,
    val validUntil: Instant,
) {
    fun calculate(listAmount: BigDecimal, performanceId: PerformanceId, now: Instant): DiscountResult {
        val reason = when {
            now < validFrom || !now.isBefore(validUntil) -> RejectReason.OUT_OF_VALID_PERIOD
            this.performanceId != null && this.performanceId != performanceId -> RejectReason.PERFORMANCE_MISMATCH
            listAmount < minOrderAmount -> RejectReason.BELOW_MIN_ORDER_AMOUNT
            else -> null
        }
        if (reason != null) return DiscountResult.Rejected(reason)

        val discount = rawDiscount(listAmount)
        return if (listAmount - discount < BigDecimal.ONE) {
            DiscountResult.Rejected(RejectReason.AMOUNT_BELOW_MINIMUM)
        } else {
            DiscountResult.Accepted(discount)
        }
    }

    private fun rawDiscount(listAmount: BigDecimal): BigDecimal = when (type) {
        DiscountType.FIXED -> value

        DiscountType.RATE -> {
            val rated = listAmount.multiply(value).divide(PERCENT, 0, RoundingMode.DOWN)
            maxDiscount?.let { rated.min(it) } ?: rated
        }
    }

    private companion object {
        private val PERCENT = BigDecimal(100)
    }
}
