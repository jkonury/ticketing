package io.github.ticketing.coupon

import io.github.ticketing.DomainException
import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.MutableClock
import io.github.ticketing.coupon.application.port.provided.ClaimCouponUseCase
import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.application.port.required.IssuedCouponCommandPort
import io.github.ticketing.coupon.domain.CouponDefinition
import io.github.ticketing.coupon.domain.DiscountPolicy
import io.github.ticketing.coupon.domain.DiscountType
import io.github.ticketing.coupon.domain.IssueMode
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.springframework.data.redis.core.StringRedisTemplate
import ticketing.jooq.coupon.tables.references.COUPON_DEFINITION
import ticketing.jooq.coupon.tables.references.ISSUED_COUPON
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** 선착순 받기 통합 테스트 공통 도구. 전략별 하위 클래스가 프로퍼티만 다르게 준다. */
abstract class LimitedCouponClaimTestSupport(
    protected val claimUseCase: ClaimCouponUseCase,
    protected val definitions: CouponDefinitionCommandPort,
    protected val issuedCoupons: IssuedCouponCommandPort,
    protected val dsl: DSLContext,
    protected val redis: StringRedisTemplate,
    private val clock: MutableClock,
) : IntegrationTestSupport() {
    protected val now: Instant = Instant.parse("2026-11-11T00:00:00Z")

    @BeforeEach
    fun fixClock() {
        clock.now = now
    }

    protected fun nextMemberId(): Long = memberSequence.incrementAndGet()

    protected fun limitedDefinition(
        quantity: Int,
        validFrom: Instant = now.minus(1, ChronoUnit.DAYS),
        validUntil: Instant = now.plus(30, ChronoUnit.DAYS),
    ): Long {
        val policy = DiscountPolicy(DiscountType.FIXED, BigDecimal(1000), null, BigDecimal.ZERO, null, validFrom, validUntil)
        val definition = definitions.save(CouponDefinition.create("선착순 쿠폰", null, policy, IssueMode.LIMITED, quantity, now))
        val id = requireNotNull(definition.id).value
        redis.delete(listOf(stockKey(id), claimedKey(id)))
        return id
    }

    protected fun stockKey(definitionId: Long) = "coupon:{$definitionId}:stock"

    protected fun claimedKey(definitionId: Long) = "coupon:{$definitionId}:claimed"

    protected fun issuedCount(definitionId: Long): Int = dsl.fetchCount(ISSUED_COUPON, ISSUED_COUPON.DEFINITION_ID.eq(definitionId))

    protected fun definitionRow(definitionId: Long) =
        requireNotNull(dsl.selectFrom(COUPON_DEFINITION).where(COUPON_DEFINITION.ID.eq(definitionId)).fetchOne())

    /** 받기 결과를 "OK" 또는 오류 코드/예외 클래스명으로 돌려준다. */
    protected fun claimOutcome(memberId: Long, definitionId: Long): String = try {
        claimUseCase.claim(memberId, definitionId)
        "OK"
    } catch (e: DomainException) {
        e.code
    } catch (e: RuntimeException) {
        e.javaClass.simpleName
    }

    protected fun claimConcurrently(definitionId: Long, memberIds: List<Long>): List<String> {
        val startGate = CountDownLatch(1)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = memberIds.map { memberId ->
                executor.submit<String> {
                    startGate.await()
                    claimOutcome(memberId, definitionId)
                }
            }
            startGate.countDown()
            return futures.map { it.get() }
        }
    }

    private companion object {
        // 전략별 컨텍스트가 같은 DB를 쓰지 않아도 회원 번호가 겹치지 않게 JVM 단위로 둔다
        val memberSequence = AtomicLong(900_000)
    }
}
