package io.github.ticketing.coupon.application.service

import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.application.port.required.IssueResult
import io.github.ticketing.coupon.application.port.required.IssuedCouponCommandPort
import io.github.ticketing.coupon.domain.CouponDefinition
import io.github.ticketing.coupon.domain.CouponDefinitionId
import io.github.ticketing.coupon.domain.IssuedCoupon
import io.github.ticketing.coupon.domain.MemberId
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.interceptor.TransactionAspectSupport
import java.time.Instant

/**
 * 한 트랜잭션에서 `remaining_quantity`를 조건부로 줄이고 발급한다(Redis 없이 DB 행 하나가 직렬화 지점).
 * 이미 받은 회원이면 롤백해 줄인 재고를 되돌린다. 이 전략에서만 `remaining_quantity`를 갱신하며,
 * 실험 중 전략 전환(redis ↔ db)은 지원하지 않는다.
 */
@Service
@ConditionalOnProperty(prefix = "ticketing.coupon.limited-claim", name = ["strategy"], havingValue = "db")
internal class DbLimitedCouponClaimer(private val starter: CouponIssueStarter, private val transaction: DbLimitedClaimTransaction) :
    LimitedCouponClaimer {
    override fun claim(definition: CouponDefinition, memberId: MemberId, now: Instant): LimitedClaimResult {
        val definitionId = checkNotNull(definition.id) { "저장된 쿠폰 정의여야 한다" }
        if (!definition.issueStarted) starter.ensureStarted(definitionId, now)

        return transaction.claim(definitionId, memberId, now)
    }
}

@Service
@ConditionalOnProperty(prefix = "ticketing.coupon.limited-claim", name = ["strategy"], havingValue = "db")
internal class DbLimitedClaimTransaction(
    private val definitions: CouponDefinitionCommandPort,
    private val issuedCoupons: IssuedCouponCommandPort,
) {
    @Transactional
    fun claim(definitionId: CouponDefinitionId, memberId: MemberId, now: Instant): LimitedClaimResult {
        if (!definitions.decrementRemainingIfAvailable(definitionId)) return LimitedClaimResult.SoldOut

        return when (val result = issuedCoupons.issue(IssuedCoupon.issue(definitionId, memberId, now))) {
            is IssueResult.Issued -> LimitedClaimResult.Issued(result.coupon)

            IssueResult.AlreadyClaimed -> {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
                LimitedClaimResult.AlreadyClaimed
            }
        }
    }
}
