package io.github.ticketing.coupon.adapter.required.jpa

import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.domain.CouponDefinition
import io.github.ticketing.coupon.domain.CouponDefinitionId
import org.jooq.DSLContext
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Repository
import ticketing.jooq.coupon.tables.references.COUPON_DEFINITION

@Repository
internal class CouponDefinitionPersistenceAdapter(private val definitions: CouponDefinitionJpaRepository, private val dsl: DSLContext) :
    CouponDefinitionCommandPort {
    override fun save(definition: CouponDefinition): CouponDefinition {
        val id = definition.id ?: return definitions.saveAndFlush(definition.toNewEntity()).toDomain()
        val entity = definitions.findById(id.value).orElseThrow { IllegalStateException("쿠폰 정의를 찾을 수 없다: ${id.value}") }
        // 도메인이 읽은 시점의 버전과 다르면 그 사이 다른 트랜잭션이 갱신한 것이다
        if (entity.version != definition.version) {
            throw ObjectOptimisticLockingFailureException(CouponDefinitionJpaEntity::class.java, id.value)
        }
        // 수량은 발급 시작 전에만 바뀐다. 시작 뒤 저장이 DB 전략이 줄인 남은 수량을 되돌리지 않도록 바뀐 경우에만 맞춘다.
        val quantityChanged = entity.totalQuantity != definition.totalQuantity
        entity.applyChanges(definition)
        val saved = definitions.saveAndFlush(entity).toDomain()
        if (quantityChanged) {
            dsl.update(COUPON_DEFINITION)
                .set(COUPON_DEFINITION.REMAINING_QUANTITY, definition.totalQuantity)
                .where(COUPON_DEFINITION.ID.eq(id.value))
                .execute()
        }
        return saved
    }

    override fun findById(id: CouponDefinitionId): CouponDefinition? = definitions.findById(id.value).map { it.toDomain() }.orElse(null)

    override fun decrementRemainingIfAvailable(id: CouponDefinitionId): Boolean = dsl.update(COUPON_DEFINITION)
        .set(COUPON_DEFINITION.REMAINING_QUANTITY, COUPON_DEFINITION.REMAINING_QUANTITY.minus(1))
        .where(COUPON_DEFINITION.ID.eq(id.value), COUPON_DEFINITION.REMAINING_QUANTITY.gt(0))
        .execute() == 1
}
