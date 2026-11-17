package io.github.ticketing.coupon.application.service

import io.github.ticketing.coupon.application.port.required.CouponStockPort
import io.github.ticketing.coupon.application.port.required.IssueResult
import io.github.ticketing.coupon.application.port.required.IssuedCouponCommandPort
import io.github.ticketing.coupon.application.port.required.StockClaimResult
import io.github.ticketing.coupon.domain.CouponDefinition
import io.github.ticketing.coupon.domain.CouponDefinitionId
import io.github.ticketing.coupon.domain.IssuedCoupon
import io.github.ticketing.coupon.domain.MemberId
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Redis Lua(재고 + 받은 회원 Set)로 선착순을 가린 뒤 같은 요청에서 동기로 DB에 INSERT한다.
 * Redis 성공만으로 발급 완료로 보지 않는다. 최종 방어선은 DB의 `(definition_id, member_id)` 유니크 제약이다.
 *
 * Redis의 `remaining_quantity`는 이 전략에서 갱신하지 않는다. 실험 중 전략 전환(redis ↔ db)은 지원하지 않는다.
 */
@Service
@ConditionalOnProperty(prefix = "ticketing.coupon.limited-claim", name = ["strategy"], havingValue = "redis", matchIfMissing = true)
internal class RedisLimitedCouponClaimer(
    private val stock: CouponStockPort,
    private val issuedCoupons: IssuedCouponCommandPort,
    private val starter: CouponIssueStarter,
) : LimitedCouponClaimer {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun claim(definition: CouponDefinition, memberId: MemberId, now: Instant): LimitedClaimResult {
        val definitionId = checkNotNull(definition.id) { "저장된 쿠폰 정의여야 한다" }

        var reply = stock.claim(definitionId, memberId)
        if (reply == StockClaimResult.NOT_INITIALIZED) {
            open(definition, definitionId, now)
            reply = stock.claim(definitionId, memberId)
        }

        return when (reply) {
            StockClaimResult.OK -> issueReserved(definitionId, memberId, now)
            StockClaimResult.DUPLICATE -> LimitedClaimResult.AlreadyClaimed
            StockClaimResult.SOLD_OUT -> LimitedClaimResult.SoldOut
            StockClaimResult.NOT_INITIALIZED -> error("쿠폰 재고 초기화 뒤에도 키가 없다. definitionId=${definitionId.value}")
        }
    }

    /** 오픈 처리. 발급 시작 표시를 저장하고, DB 발급 현황을 기준으로 Redis 재고를 만든다(이미 있으면 아무것도 하지 않는다). */
    private fun open(definition: CouponDefinition, definitionId: CouponDefinitionId, now: Instant) {
        if (!definition.issueStarted) starter.ensureStarted(definitionId, now)

        val claimedMemberIds = issuedCoupons.findMemberIdsByDefinition(definitionId)
        val total = checkNotNull(definition.totalQuantity) { "LIMITED 쿠폰은 수량이 있다" }
        stock.initializeIfAbsent(definitionId, (total - claimedMemberIds.size).coerceAtLeast(0), claimedMemberIds)
    }

    private fun issueReserved(definitionId: CouponDefinitionId, memberId: MemberId, now: Instant): LimitedClaimResult {
        var completed = false
        val result = try {
            issuedCoupons.issue(IssuedCoupon.issue(definitionId, memberId, now)).also { completed = true }
        } finally {
            // DB 예외는 그대로 전파하되 선점한 재고와 회원 표시는 되돌린다
            if (!completed) compensate(definitionId, memberId, removeMember = true)
        }

        return when (result) {
            is IssueResult.Issued -> LimitedClaimResult.Issued(result.coupon)

            IssueResult.AlreadyClaimed -> {
                // Set이 유실됐을 뿐 그 회원은 실제로 받았다. 재고만 되돌리고 회원 표시는 남긴다
                compensate(definitionId, memberId, removeMember = false)
                LimitedClaimResult.AlreadyClaimed
            }
        }
    }

    private fun compensate(definitionId: CouponDefinitionId, memberId: MemberId, removeMember: Boolean) {
        try {
            stock.unclaim(definitionId, memberId, removeMember)
        } catch (e: DataAccessException) {
            log.error("쿠폰 재고 보상 실패(보정 Job이 맞춘다). definitionId={}, memberId={}", definitionId.value, memberId.value, e)
        }
    }
}
