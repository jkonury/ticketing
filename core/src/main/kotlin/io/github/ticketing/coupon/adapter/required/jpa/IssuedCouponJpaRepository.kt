package io.github.ticketing.coupon.adapter.required.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.math.BigDecimal
import java.time.Instant

/** 상태 전이는 조건이 WHERE에 들어간 벌크 UPDATE로 처리하고 version을 직접 올린다. */
internal interface IssuedCouponJpaRepository : JpaRepository<IssuedCouponJpaEntity, Long> {
    fun findByDefinitionIdAndMemberId(definitionId: Long, memberId: Long): IssuedCouponJpaEntity?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update IssuedCouponJpaEntity c set c.status = 'RESERVED', c.orderId = :orderId, c.discountAmount = :discount, " +
            "c.reservedUntil = :reservedUntil, c.version = c.version + 1 where c.id = :id and c.status = 'ISSUED'",
    )
    fun reserveIfIssued(id: Long, orderId: Long, discount: BigDecimal, reservedUntil: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update IssuedCouponJpaEntity c set c.status = 'ISSUED', c.orderId = null, c.discountAmount = null, " +
            "c.reservedUntil = null, c.version = c.version + 1 where c.id = :id and c.status = 'RESERVED' and c.orderId = :orderId",
    )
    fun releaseIfReservedBy(id: Long, orderId: Long): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update IssuedCouponJpaEntity c set c.status = 'ISSUED', c.orderId = null, c.discountAmount = null, " +
            "c.reservedUntil = null, c.version = c.version + 1 " +
            "where c.id = :id and c.status = 'RESERVED' and c.reservedUntil < :cutoff",
    )
    fun releaseIfStale(id: Long, cutoff: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update IssuedCouponJpaEntity c set c.status = 'USED', c.usedAt = :now, c.version = c.version + 1 " +
            "where c.id = :id and c.status = 'RESERVED' and c.orderId = :orderId",
    )
    fun confirmIfReservedBy(id: Long, orderId: Long, now: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update IssuedCouponJpaEntity c set c.status = 'REVOKED', c.version = c.version + 1 where c.id = :id and c.status = 'ISSUED'")
    fun revokeIfIssued(id: Long): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update IssuedCouponJpaEntity c set c.status = 'USED', c.orderId = :orderId, c.usedAt = :now, " +
            "c.reservedUntil = null, c.version = c.version + 1 where c.id = :id",
    )
    fun forceUse(id: Long, orderId: Long, now: Instant): Int
}
