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
}
