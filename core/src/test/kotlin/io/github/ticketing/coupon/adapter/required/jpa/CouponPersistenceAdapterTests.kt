package io.github.ticketing.coupon.adapter.required.jpa

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.application.port.required.IssueResult
import io.github.ticketing.coupon.application.port.required.IssuedCouponCommandPort
import io.github.ticketing.coupon.domain.CouponDefinition
import io.github.ticketing.coupon.domain.DiscountPolicy
import io.github.ticketing.coupon.domain.DiscountType
import io.github.ticketing.coupon.domain.IssueMode
import io.github.ticketing.coupon.domain.IssuedCoupon
import io.github.ticketing.coupon.domain.IssuedCouponId
import io.github.ticketing.coupon.domain.IssuedCouponStatus
import io.github.ticketing.coupon.domain.MemberId
import io.github.ticketing.coupon.domain.OrderId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class CouponPersistenceAdapterTests(
    private val definitions: CouponDefinitionCommandPort,
    private val issuedCoupons: IssuedCouponCommandPort,
    private val tx: TransactionTemplate,
) : IntegrationTestSupport() {

    private val now = Instant.parse("2026-11-10T10:00:00Z")
    private val ids = AtomicLong(System.nanoTime() % 1_000_000_000L * 100)
    private val discount = BigDecimal(1_000)

    private fun newDefinition() = CouponDefinition.create(
        "테스트 쿠폰",
        null,
        DiscountPolicy(
            DiscountType.RATE,
            BigDecimal(10),
            BigDecimal(5_000),
            BigDecimal.ZERO,
            null,
            now.minusSeconds(3600),
            now.plusSeconds(3600),
        ),
        IssueMode.UNLIMITED,
        null,
        now,
    )

    private fun issuedCoupon(): IssuedCoupon {
        val definition = requireNotNull(definitions.save(newDefinition()).id)
        val result = issuedCoupons.issue(IssuedCoupon.issue(definition, MemberId(ids.incrementAndGet()), now))
        return (result as IssueResult.Issued).coupon
    }

    private fun <T> inTx(block: () -> T): T = requireNotNull(tx.execute { block() })

    private fun IssuedCoupon.couponId() = requireNotNull(id)

    private fun IssuedCoupon.reloaded() = requireNotNull(issuedCoupons.findById(couponId()))

    @Test
    fun `정의를 저장하고 조회하면 값이 복원된다`() {
        val saved = definitions.save(newDefinition())

        val found = requireNotNull(definitions.findById(requireNotNull(saved.id)))

        assertThat(found.discountPolicy.maxDiscount).isEqualByComparingTo("5000")
        assertThat(found.discountPolicy.type).isEqualTo(DiscountType.RATE)
        assertThat(found.validUntil).isEqualTo(now.plusSeconds(3600))
    }

    @Test
    fun `정의의 변경을 저장하면 버전이 오르고 오래된 버전은 거부된다`() {
        val saved = definitions.save(newDefinition())
        val stale = requireNotNull(definitions.findById(requireNotNull(saved.id)))
        saved.markIssueStarted(now)
        val updated = definitions.save(saved)

        assertThat(updated.version).isEqualTo(saved.version + 1)
        assertThat(updated.issueStartedAt).isEqualTo(now)
        org.junit.jupiter.api.assertThrows<ObjectOptimisticLockingFailureException> { definitions.save(stale) }
    }

    @Test
    fun `같은 회원이 같은 정의를 다시 발급받으면 AlreadyClaimed이고 트랜잭션은 살아 있다`() {
        val definitionId = requireNotNull(definitions.save(newDefinition()).id)
        val coupon = IssuedCoupon.issue(definitionId, MemberId(ids.incrementAndGet()), now)

        val results = tx.execute { listOf(issuedCoupons.issue(coupon), issuedCoupons.issue(coupon)) }

        assertThat(results.get(0)).isInstanceOf(IssueResult.Issued::class.java)
        assertThat(results.get(1)).isEqualTo(IssueResult.AlreadyClaimed)
    }

    @Test
    fun `예약은 ISSUED일 때만 성공한다`() {
        val coupon = issuedCoupon()
        val orderId = OrderId(ids.incrementAndGet())

        val first = inTx { issuedCoupons.reserveIfIssued(coupon.couponId(), orderId, discount, now.plusSeconds(900)) }
        val second =
            inTx { issuedCoupons.reserveIfIssued(coupon.couponId(), OrderId(ids.incrementAndGet()), discount, now.plusSeconds(900)) }

        val reloaded = coupon.reloaded()
        assertThat(first).isTrue()
        assertThat(second).isFalse()
        assertThat(reloaded.status).isEqualTo(IssuedCouponStatus.RESERVED)
        assertThat(reloaded.orderId).isEqualTo(orderId)
        assertThat(reloaded.discountAmount).isEqualByComparingTo("1000")
        assertThat(reloaded.version).isEqualTo(coupon.version + 1)
    }

    @Test
    fun `해제는 예약한 주문만 할 수 있고 재전달은 무시된다`() {
        val coupon = issuedCoupon()
        val orderId = OrderId(ids.incrementAndGet())
        inTx { issuedCoupons.reserveIfIssued(coupon.couponId(), orderId, discount, now.plusSeconds(900)) }

        val otherOrder = inTx { issuedCoupons.releaseIfReservedBy(coupon.couponId(), OrderId(ids.incrementAndGet())) }
        val owner = inTx { issuedCoupons.releaseIfReservedBy(coupon.couponId(), orderId) }
        val redelivered = inTx { issuedCoupons.releaseIfReservedBy(coupon.couponId(), orderId) }

        val reloaded = coupon.reloaded()
        assertThat(otherOrder).isFalse()
        assertThat(owner).isTrue()
        assertThat(redelivered).isFalse()
        assertThat(reloaded.status).isEqualTo(IssuedCouponStatus.ISSUED)
        assertThat(reloaded.orderId).isNull()
        assertThat(reloaded.discountAmount).isNull()
        assertThat(reloaded.reservedUntil).isNull()
    }

    @Test
    fun `사용 확정은 예약한 주문만 할 수 있다`() {
        val coupon = issuedCoupon()
        val orderId = OrderId(ids.incrementAndGet())
        inTx { issuedCoupons.reserveIfIssued(coupon.couponId(), orderId, discount, now.plusSeconds(900)) }

        val otherOrder = inTx { issuedCoupons.confirmIfReservedBy(coupon.couponId(), OrderId(ids.incrementAndGet()), now) }
        val owner = inTx { issuedCoupons.confirmIfReservedBy(coupon.couponId(), orderId, now) }
        val afterUsed = inTx { issuedCoupons.releaseIfReservedBy(coupon.couponId(), orderId) }

        val reloaded = coupon.reloaded()
        assertThat(otherOrder).isFalse()
        assertThat(owner).isTrue()
        assertThat(afterUsed).isFalse()
        assertThat(reloaded.status).isEqualTo(IssuedCouponStatus.USED)
        assertThat(reloaded.usedAt).isEqualTo(now)
    }

    @Test
    fun `회수는 ISSUED일 때만 성공한다`() {
        val revocable = issuedCoupon()
        val reserved = issuedCoupon()
        inTx { issuedCoupons.reserveIfIssued(reserved.couponId(), OrderId(ids.incrementAndGet()), discount, now.plusSeconds(900)) }

        assertThat(inTx { issuedCoupons.revokeIfIssued(revocable.couponId()) }).isTrue()
        assertThat(inTx { issuedCoupons.revokeIfIssued(revocable.couponId()) }).isFalse()
        assertThat(inTx { issuedCoupons.revokeIfIssued(reserved.couponId()) }).isFalse()
        assertThat(revocable.reloaded().status).isEqualTo(IssuedCouponStatus.REVOKED)
        assertThat(inTx { issuedCoupons.revokeIfIssued(IssuedCouponId(-1)) }).isFalse()
    }
}
