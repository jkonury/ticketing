package io.github.ticketing.settlement.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class DailySettlementCalculatorTests {
    private fun calculate(sale: String, refund: String) = DailySettlementCalculator.settle(
        LocalDate.parse("2026-11-11"), 1, 2, 1, BigDecimal(sale), 1, BigDecimal(refund),
        FeeRate(BigDecimal("0.10")), Instant.EPOCH,
    )

    @Test
    fun `수수료는 양수와 음수 모두 0 방향으로 절사한다`() {
        assertThat(calculate("19", "0").feeAmount).isEqualByComparingTo("1")
        assertThat(calculate("0", "19").feeAmount).isEqualByComparingTo("-1")
        assertThat(calculate("0", "19").payoutAmount).isEqualByComparingTo("-18")
    }

    @Test
    fun `수수료율은 0 이상 1 미만이어야 한다`() {
        assertThat(FeeRate(BigDecimal.ZERO).value).isEqualByComparingTo("0")
        assertThatIllegalArgumentException().isThrownBy { FeeRate(BigDecimal("-0.01")) }
        assertThatIllegalArgumentException().isThrownBy { FeeRate(BigDecimal.ONE) }
    }

    @Test
    fun `플랫폼 할인 부담액은 수수료와 지급액 계산에 영향을 주지 않는다`() {
        val result = DailySettlementCalculator.settle(
            LocalDate.parse("2026-11-11"), 1, 2, 1, BigDecimal("10000"), 0, BigDecimal.ZERO,
            FeeRate(BigDecimal("0.10")), Instant.EPOCH, BigDecimal("2000"),
        )
        assertThat(result.platformDiscountAmount).isEqualByComparingTo("2000")
        assertThat(result.feeAmount).isEqualByComparingTo("1000")
        assertThat(result.payoutAmount).isEqualByComparingTo("9000")
    }
}
