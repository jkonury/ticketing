package io.github.ticketing.coupon.application.port.required

import io.github.ticketing.coupon.domain.CouponDefinitionId
import io.github.ticketing.coupon.domain.MemberId

enum class StockClaimResult { OK, DUPLICATE, SOLD_OUT, NOT_INITIALIZED }

/**
 * 선착순 쿠폰의 Redis 재고. 재고와 받은 회원 Set은 한 Lua 안에서 함께 바뀐다.
 * Redis와 DB는 한 트랜잭션이 아니므로 호출자가 DB 실패 시 [unclaim]으로 되돌린다.
 */
interface CouponStockPort {
    fun claim(definitionId: CouponDefinitionId, memberId: MemberId): StockClaimResult

    /** 재고를 1 되돌린다. [removeMember]가 true면 받은 회원 Set에서도 제거한다(실제로 받은 회원이면 false). */
    fun unclaim(definitionId: CouponDefinitionId, memberId: MemberId, removeMember: Boolean)

    /** 재고 키가 없을 때만 만든다. 이미 있으면 아무것도 하지 않는다. */
    fun initializeIfAbsent(definitionId: CouponDefinitionId, remaining: Int, claimedMemberIds: List<MemberId>)

    /**
     * Redis를 DB 기준으로 보수적으로만 고친다. 바꾼 건수를 돌려준다.
     * 키가 없으면 [initializeIfAbsent]처럼 구성한다. 있으면 재고는 [remaining]보다 클 때만 낮추고(올리지 않는다),
     * 받은 회원 Set에는 [claimedMemberIds] 중 빠진 회원만 더한다(Set에만 있는 회원은 지우지 않는다).
     * 올리지 않으므로 보상 실패 등으로 Redis 재고가 DB보다 작아진 "누수 재고"는 이 호출로 복구되지 않는다.
     */
    fun reconcile(definitionId: CouponDefinitionId, remaining: Int, claimedMemberIds: List<MemberId>): Int
}
