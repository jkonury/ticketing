package io.github.ticketing.coupon.adapter.required.jpa

import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.domain.CouponDefinition
import io.github.ticketing.coupon.domain.CouponDefinitionId
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Repository

@Repository
internal class CouponDefinitionPersistenceAdapter(private val definitions: CouponDefinitionJpaRepository) : CouponDefinitionCommandPort {
    override fun save(definition: CouponDefinition): CouponDefinition {
        val id = definition.id ?: return definitions.saveAndFlush(definition.toNewEntity()).toDomain()
        val entity = definitions.findById(id.value).orElseThrow { IllegalStateException("쿠폰 정의를 찾을 수 없다: ${id.value}") }
        // 도메인이 읽은 시점의 버전과 다르면 그 사이 다른 트랜잭션이 갱신한 것이다
        if (entity.version != definition.version) {
            throw ObjectOptimisticLockingFailureException(CouponDefinitionJpaEntity::class.java, id.value)
        }
        entity.applyChanges(definition)
        return definitions.saveAndFlush(entity).toDomain()
    }

    override fun findById(id: CouponDefinitionId): CouponDefinition? = definitions.findById(id.value).map { it.toDomain() }.orElse(null)
}
