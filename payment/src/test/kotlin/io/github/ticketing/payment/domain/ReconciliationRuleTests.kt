package io.github.ticketing.payment.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ReconciliationRuleTests {
    private val internal = InternalPaymentRecord(1, PaymentStatus.REQUESTED, BigDecimal("100.00"), "tx")
    private val pg = PgTransactionRecord("tx", ReconciliationPgStatus.PENDING, BigDecimal("100"))

    @Test
    fun `상태 대응표의 모든 쌍은 일치하고 나머지는 불일치한다`() {
        val expected = mapOf(
            PaymentStatus.REQUESTED to ReconciliationPgStatus.PENDING,
            PaymentStatus.SUCCEEDED to ReconciliationPgStatus.APPROVED,
            PaymentStatus.REFUND_REQUESTED to ReconciliationPgStatus.APPROVED,
            PaymentStatus.REFUNDED to ReconciliationPgStatus.REFUNDED,
            PaymentStatus.FAILED to ReconciliationPgStatus.FAILED,
        )
        expected.forEach { (status, expectedPg) ->
            ReconciliationPgStatus.entries.forEach { pgStatus ->
                val result = ReconciliationRule.compare(internal.copy(status = status), pg.copy(status = pgStatus))
                assertThat(result).isEqualTo(
                    if (pgStatus ==
                        expectedPg
                    ) {
                        emptyList<MismatchType>()
                    } else {
                        listOf(MismatchType.STATUS_MISMATCH)
                    },
                )
            }
        }
    }

    @Test
    fun `없는 쪽과 복수 불일치를 판별하고 미확정 거래 번호는 비교하지 않는다`() {
        assertThat(ReconciliationRule.compare(null, pg)).containsExactly(MismatchType.MISSING_INTERNAL)
        assertThat(ReconciliationRule.compare(internal, null)).containsExactly(MismatchType.MISSING_PG)
        assertThat(
            ReconciliationRule.compare(
                internal,
                pg.copy(transactionId = "other", status = ReconciliationPgStatus.FAILED, amount = BigDecimal.ONE),
            ),
        )
            .containsExactly(MismatchType.STATUS_MISMATCH, MismatchType.AMOUNT_MISMATCH, MismatchType.TRANSACTION_ID_MISMATCH)
        assertThat(ReconciliationRule.compare(internal.copy(transactionId = null), pg)).isEmpty()
    }
}
