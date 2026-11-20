package io.github.ticketing.coupon.adapter.provided.batch

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.MutableClock
import io.github.ticketing.coupon.CouponUseConfirmed
import io.github.ticketing.coupon.application.port.provided.ConfirmCouponUseUseCase
import io.github.ticketing.coupon.application.port.provided.ReleaseStaleCouponReservationUseCase
import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.application.port.required.IssueResult
import io.github.ticketing.coupon.application.port.required.IssuedCouponCommandPort
import io.github.ticketing.coupon.application.port.required.StaleReservationQueryPort
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
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

class ReleaseStaleCouponReservationsJobTests(
    private val operator: JobOperator,
    private val releaseStaleCouponReservationsJob: Job,
    private val definitions: CouponDefinitionCommandPort,
    private val issuedCoupons: IssuedCouponCommandPort,
    private val queryPort: StaleReservationQueryPort,
    private val releaseUseCase: ReleaseStaleCouponReservationUseCase,
    private val confirmUseCase: ConfirmCouponUseUseCase,
    private val dsl: DSLContext,
    private val transaction: TransactionTemplate,
    private val clock: MutableClock,
) : IntegrationTestSupport() {
    @BeforeEach
    fun fixClock() {
        clock.now = NOW
    }

    @Test
    fun `유예 시간까지 지난 RESERVED만 해제하고 나머지 상태는 바꾸지 않는다`() {
        val stale = reserved(NOW.minus(11, ChronoUnit.MINUTES))
        val withinGrace = reserved(NOW.minus(9, ChronoUnit.MINUTES))
        val future = reserved(NOW.plus(1, ChronoUnit.HOURS))
        val issued = issue()
        val used = reserved(NOW.minus(1, ChronoUnit.HOURS)).also { id ->
            confirmUseCase.confirm(CouponUseConfirmed(requireNotNull(coupon(id).orderId).value, id, NOW, ATTEMPT_ID))
        }

        assertThat(operator.start(releaseStaleCouponReservationsJob, parameters(1)).status).isEqualTo(BatchStatus.COMPLETED)

        val released = coupon(stale)
        assertThat(released.status).isEqualTo(IssuedCouponStatus.ISSUED)
        assertThat(released.orderId).isNull()
        assertThat(released.discountAmount).isNull()
        assertThat(released.reservedUntil).isNull()
        assertThat(coupon(withinGrace).status).isEqualTo(IssuedCouponStatus.RESERVED)
        assertThat(coupon(future).status).isEqualTo(IssuedCouponStatus.RESERVED)
        assertThat(coupon(issued).status).isEqualTo(IssuedCouponStatus.ISSUED)
        assertThat(coupon(used).status).isEqualTo(IssuedCouponStatus.USED)
    }

    @Test
    fun `같은 cutoff로 다시 조회해도 같은 대상이고 keyset으로 나눠 읽는다`() {
        val first = reserved(NOW.minusSeconds(3600))
        val second = reserved(NOW.minusSeconds(3500))
        val cutoff = NOW.minusSeconds(600)

        val all = queryPort.findStaleReservationIds(cutoff, null, 1000).filter { it in listOf(first, second) }
        assertThat(all).containsExactly(first, second)
        assertThat(queryPort.findStaleReservationIds(cutoff, null, 1000).filter { it in listOf(first, second) }).isEqualTo(all)
        assertThat(queryPort.findStaleReservationIds(cutoff, first, 1000)).doesNotContain(first).contains(second)
    }

    @Test
    fun `리더가 읽은 뒤 확정된 쿠폰은 해제하지 않는다`() {
        val id = reserved(NOW.minusSeconds(3600))
        val orderId = requireNotNull(coupon(id).orderId).value
        val cutoff = NOW.minusSeconds(600)
        assertThat(queryPort.findStaleReservationIds(cutoff, null, 1000)).contains(id)

        confirmUseCase.confirm(CouponUseConfirmed(orderId, id, NOW, ATTEMPT_ID))

        assertThat(releaseUseCase.releaseIfStale(id, cutoff)).isFalse()
        assertThat(coupon(id).status).isEqualTo(IssuedCouponStatus.USED)
    }

    @Test
    fun `해제된 뒤 늦은 사용 확정이 오면 강제로 USED로 바꾸고 불일치를 기록한다`() {
        val id = reserved(NOW.minusSeconds(3600))
        val orderId = requireNotNull(coupon(id).orderId).value

        assertThat(operator.start(releaseStaleCouponReservationsJob, parameters(2)).status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(coupon(id).status).isEqualTo(IssuedCouponStatus.ISSUED)

        confirmUseCase.confirm(CouponUseConfirmed(orderId, id, NOW, ATTEMPT_ID))

        val forced = coupon(id)
        assertThat(forced.status).isEqualTo(IssuedCouponStatus.USED)
        assertThat(forced.orderId).isEqualTo(OrderId(orderId))
        val previous = dsl.fetchOne("SELECT previous_status FROM coupon.use_mismatch WHERE issued_coupon_id = ?", id)
        assertThat(previous?.get("previous_status")).isEqualTo("ISSUED")
    }

    private fun parameters(run: Long) = JobParametersBuilder().addLong("run.id", run).toJobParameters()

    private fun coupon(id: Long) = requireNotNull(transaction.execute { issuedCoupons.findById(IssuedCouponId(id)) })

    private fun issue(): Long {
        val policy = DiscountPolicy(
            DiscountType.FIXED,
            BigDecimal(1000),
            null,
            BigDecimal.ZERO,
            null,
            NOW.minus(1, ChronoUnit.DAYS),
            NOW.plus(30, ChronoUnit.DAYS),
        )
        val definition = definitions.save(CouponDefinition.create("정리 Job 테스트", null, policy, IssueMode.DIRECT, null, NOW))
        val issued = issuedCoupons.issue(IssuedCoupon.issue(requireNotNull(definition.id), MemberId(memberSeq.incrementAndGet()), NOW))
        return requireNotNull((issued as IssueResult.Issued).coupon.id).value
    }

    private fun reserved(reservedUntil: Instant): Long {
        val id = issue()
        val ok = transaction.execute {
            issuedCoupons.reserveIfAvailable(
                IssuedCouponId(id),
                OrderId(orderSeq.incrementAndGet()),
                BigDecimal(1000),
                reservedUntil,
                ATTEMPT_ID,
            )
        }
        assertThat(ok).isTrue()
        return id
    }

    private companion object {
        const val ATTEMPT_ID = 1L

        val NOW: Instant = Instant.parse("2026-11-11T12:00:00Z")
        val orderSeq = AtomicLong(System.nanoTime())
        val memberSeq = AtomicLong(System.nanoTime() % 1_000_000_000L)
    }
}
