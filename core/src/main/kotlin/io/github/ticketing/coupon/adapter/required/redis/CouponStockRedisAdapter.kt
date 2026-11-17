package io.github.ticketing.coupon.adapter.required.redis

import io.github.ticketing.coupon.application.port.required.CouponStockPort
import io.github.ticketing.coupon.application.port.required.StockClaimResult
import io.github.ticketing.coupon.domain.CouponDefinitionId
import io.github.ticketing.coupon.domain.MemberId
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

@Configuration(proxyBeanMethods = false)
internal class CouponStockScriptConfig {
    @Bean
    fun couponClaimScript(): RedisScript<String> = DefaultRedisScript(load("claim"), String::class.java)

    @Bean
    fun couponUnclaimScript(): RedisScript<Long> = DefaultRedisScript(load("unclaim"), Long::class.java)

    @Bean
    fun couponInitScript(): RedisScript<Long> = DefaultRedisScript(load("init"), Long::class.java)

    @Bean
    fun couponReconcileScript(): RedisScript<Long> = DefaultRedisScript(load("reconcile"), Long::class.java)

    private fun load(name: String): String =
        ClassPathResource("redis/coupon/$name.lua").inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
}

/** 두 키에 `{definitionId}` 해시 태그를 넣어 Redis Cluster에서도 한 슬롯에 모은다. */
@Component
internal class CouponStockRedisAdapter(
    private val redis: StringRedisTemplate,
    @Qualifier("couponClaimScript") private val claimScript: RedisScript<String>,
    @Qualifier("couponUnclaimScript") private val unclaimScript: RedisScript<Long>,
    @Qualifier("couponInitScript") private val initScript: RedisScript<Long>,
    @Qualifier("couponReconcileScript") private val reconcileScript: RedisScript<Long>,
) : CouponStockPort {
    override fun claim(definitionId: CouponDefinitionId, memberId: MemberId): StockClaimResult {
        val reply = redis.execute(claimScript, keys(definitionId), memberId.value.toString())
        return when (reply) {
            "OK" -> StockClaimResult.OK
            "DUPLICATE" -> StockClaimResult.DUPLICATE
            "SOLD_OUT" -> StockClaimResult.SOLD_OUT
            "NOT_INITIALIZED" -> StockClaimResult.NOT_INITIALIZED
            else -> error("알 수 없는 쿠폰 재고 응답: $reply")
        }
    }

    override fun unclaim(definitionId: CouponDefinitionId, memberId: MemberId, removeMember: Boolean) {
        redis.execute(unclaimScript, keys(definitionId), memberId.value.toString(), if (removeMember) "1" else "0")
    }

    override fun initializeIfAbsent(definitionId: CouponDefinitionId, remaining: Int, claimedMemberIds: List<MemberId>) {
        val args = listOf(remaining.toString()) + claimedMemberIds.map { it.value.toString() }
        redis.execute(initScript, keys(definitionId), *args.toTypedArray())
    }

    override fun reconcile(definitionId: CouponDefinitionId, remaining: Int, claimedMemberIds: List<MemberId>): Int {
        val args = listOf(remaining.toString()) + claimedMemberIds.map { it.value.toString() }
        return (redis.execute(reconcileScript, keys(definitionId), *args.toTypedArray()) ?: 0L).toInt()
    }

    private fun keys(definitionId: CouponDefinitionId) = listOf(
        "coupon:{${definitionId.value}}:stock",
        "coupon:{${definitionId.value}}:claimed",
    )
}
