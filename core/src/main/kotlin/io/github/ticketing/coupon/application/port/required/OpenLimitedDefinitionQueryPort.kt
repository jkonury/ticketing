package io.github.ticketing.coupon.application.port.required

/** Redis 재고 보정 Job용 조회. */
interface OpenLimitedDefinitionQueryPort {
    /** LIMITED이면서 발급이 시작된 정의 id를 id 오름차순 keyset으로 돌려준다. */
    fun findOpenLimitedIds(afterId: Long?, limit: Int): List<Long>
}
