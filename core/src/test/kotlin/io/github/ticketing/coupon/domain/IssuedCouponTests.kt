package io.github.ticketing.coupon.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IssuedCouponTests {

    private val now = Instant.parse("2026-11-10T10:00:00Z")
    private val order = OrderId(1)
    private val discount = BigDecimal(1_000)

    private fun issued() = IssuedCoupon.issue(CouponDefinitionId(1), MemberId(1), now)

    private fun reserved() = issued().also { it.reserve(order, discount, now.plusSeconds(900)) }

    private fun codeOf(block: () -> Unit) = assertThrows<CouponRuleException>(block).code

    @Test
    fun `발급하면 ISSUED이다`() {
        assertEquals(IssuedCouponStatus.ISSUED, issued().status)
    }

    @Test
    fun `예약하면 주문과 할인액과 예약 만료 시각이 남는다`() {
        val coupon = reserved()

        assertEquals(IssuedCouponStatus.RESERVED, coupon.status)
        assertEquals(order, coupon.orderId)
        assertEquals(discount, coupon.discountAmount)
        assertEquals(now.plusSeconds(900), coupon.reservedUntil)
    }

    @Test
    fun `이미 예약된 쿠폰은 다시 예약할 수 없다`() {
        assertEquals(INVALID_COUPON_STATE, codeOf { reserved().reserve(OrderId(2), discount, now) })
    }

    @Test
    fun `해제하면 ISSUED로 복원되고 주문 정보가 비워진다`() {
        val coupon = reserved()

        coupon.release(order)

        assertEquals(IssuedCouponStatus.ISSUED, coupon.status)
        assertNull(coupon.orderId)
        assertNull(coupon.discountAmount)
        assertNull(coupon.reservedUntil)
    }

    @Test
    fun `다른 주문은 해제하거나 사용 확정할 수 없다`() {
        val coupon = reserved()

        assertEquals(COUPON_ORDER_MISMATCH, codeOf { coupon.release(OrderId(2)) })
        assertEquals(COUPON_ORDER_MISMATCH, codeOf { coupon.confirmUse(OrderId(2), now) })
    }

    @Test
    fun `사용 확정하면 USED이고 사용 시각이 남는다`() {
        val coupon = reserved()

        coupon.confirmUse(order, now)

        assertEquals(IssuedCouponStatus.USED, coupon.status)
        assertEquals(now, coupon.usedAt)
    }

    @Test
    fun `예약되지 않은 쿠폰은 해제하거나 사용 확정할 수 없다`() {
        assertEquals(INVALID_COUPON_STATE, codeOf { issued().release(order) })
        assertEquals(INVALID_COUPON_STATE, codeOf { issued().confirmUse(order, now) })
    }

    @Test
    fun `회수는 ISSUED 상태에서만 가능하다`() {
        val coupon = issued().also { it.revoke() }

        assertEquals(IssuedCouponStatus.REVOKED, coupon.status)
        assertEquals(INVALID_COUPON_STATE, codeOf { reserved().revoke() })
        assertEquals(INVALID_COUPON_STATE, codeOf { coupon.revoke() })
    }

    @Test
    fun `사용된 쿠폰은 해제할 수 없다`() {
        val coupon = reserved().also { it.confirmUse(order, now) }

        assertEquals(INVALID_COUPON_STATE, codeOf { coupon.release(order) })
    }
}
