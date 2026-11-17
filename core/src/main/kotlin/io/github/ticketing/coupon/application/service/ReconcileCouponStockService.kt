package io.github.ticketing.coupon.application.service

import io.github.ticketing.coupon.application.port.provided.ReconcileCouponStockUseCase
import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.application.port.required.CouponStockPort
import io.github.ticketing.coupon.application.port.required.IssuedCouponCommandPort
import io.github.ticketing.coupon.domain.CouponDefinitionId
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * 받기가 진행 중이어도 안전한 방향(재고 낮추기, Set 채우기)만 고친다. 트랜잭션 없이 DB를 읽은 뒤 Lua 한 번으로 반영한다.
 * DB를 읽은 뒤 들어온 받기는 Redis 재고를 이미 더 낮췄으므로 낮추기 조건에 걸리지 않는다.
 */
@Service
internal class ReconcileCouponStockService(
    private val definitions: CouponDefinitionCommandPort,
    private val issuedCoupons: IssuedCouponCommandPort,
    private val stock: CouponStockPort,
    @Value("\${ticketing.coupon.limited-claim.strategy:redis}") private val strategy: String,
) : ReconcileCouponStockUseCase {
    override fun reconcile(definitionId: Long): Int = if (strategy == "redis") reconcileRedis(CouponDefinitionId(definitionId)) else 0

    private fun reconcileRedis(id: CouponDefinitionId): Int {
        val total = definitions.findById(id)?.takeIf { it.issueStarted }?.totalQuantity ?: return 0
        val claimedMemberIds = issuedCoupons.findMemberIdsByDefinition(id)
        return stock.reconcile(id, (total - claimedMemberIds.size).coerceAtLeast(0), claimedMemberIds)
    }
}
