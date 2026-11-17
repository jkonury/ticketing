package io.github.ticketing.coupon.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CouponDefinitionTests {

    private val validFrom = Instant.parse("2026-11-01T00:00:00Z")
    private val validUntil = Instant.parse("2026-12-01T00:00:00Z")
    private val now = Instant.parse("2026-11-10T10:00:00Z")

    private fun policy(validUntil: Instant = this.validUntil) =
        DiscountPolicy(DiscountType.RATE, BigDecimal(10), null, BigDecimal.ZERO, null, validFrom, validUntil)

    private fun definition() = CouponDefinition.create("가을 쿠폰", null, policy(), IssueMode.UNLIMITED, null, now)

    @Test
    fun `발급 시작 전에는 할인 조건을 바꿀 수 있다`() {
        val definition = definition()

        definition.changeTerms(policy().copy(value = BigDecimal(20)), IssueMode.LIMITED, 100)

        assertEquals(BigDecimal(20), definition.discountPolicy.value)
        assertEquals(IssueMode.LIMITED, definition.issueMode)
        assertEquals(100, definition.totalQuantity)
    }

    @Test
    fun `발급이 시작되면 할인 조건은 바꿀 수 없다`() {
        val definition = definition().also { it.markIssueStarted(now) }

        val exception = assertThrows<CouponRuleException> { definition.changeTerms(policy(), IssueMode.DIRECT, null) }

        assertEquals(COUPON_DEFINITION_LOCKED, exception.code)
    }

    @Test
    fun `발급이 시작된 뒤에도 이름 변경과 종료일 연장은 가능하다`() {
        val definition = definition().also { it.markIssueStarted(now) }

        definition.rename("겨울 쿠폰", "설명")
        definition.extendValidUntil(validUntil.plusSeconds(3600))

        assertEquals("겨울 쿠폰", definition.name)
        assertEquals(validUntil.plusSeconds(3600), definition.validUntil)
    }

    @Test
    fun `종료일을 단축할 수 없다`() {
        val exception = assertThrows<CouponRuleException> { definition().extendValidUntil(validUntil.minusSeconds(1)) }

        assertEquals(COUPON_VALID_UNTIL_SHORTENED, exception.code)
    }

    @Test
    fun `발급 시작 시각은 처음 한 번만 기록한다`() {
        val definition = definition()

        definition.markIssueStarted(now)
        definition.markIssueStarted(now.plusSeconds(60))

        assertEquals(now, definition.issueStartedAt)
    }

    @Test
    fun `중지하면 신규 발급만 막는다`() {
        val definition = definition()
        assertTrue(definition.canIssue(now))

        definition.stop()

        assertFalse(definition.canIssue(now))
        assertEquals(DefinitionStatus.STOPPED, definition.status)
    }

    @Test
    fun `유효 기간 밖에서는 발급할 수 없다`() {
        val definition = definition()

        assertFalse(definition.canIssue(validFrom.minusSeconds(1)))
        assertFalse(definition.canIssue(validUntil))
    }
}
