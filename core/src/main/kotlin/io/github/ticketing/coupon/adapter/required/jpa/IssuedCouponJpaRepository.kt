package io.github.ticketing.coupon.adapter.required.jpa

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.math.BigDecimal
import java.time.Instant

/** 상태 전이는 조건이 WHERE에 들어간 벌크 UPDATE로 처리하고 version을 직접 올린다. */
internal interface IssuedCouponJpaRepository : JpaRepository<IssuedCouponJpaEntity, Long> {
    fun findByDefinitionIdAndMemberId(definitionId: Long, memberId: Long): IssuedCouponJpaEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from IssuedCouponJpaEntity c where c.id = :id")
    fun findForUpdate(id: Long): IssuedCouponJpaEntity?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update IssuedCouponJpaEntity c set c.status = 'RESERVED', c.orderId = :orderId, c.discountAmount = :discount, " +
            "c.reservationAttemptId = :attemptId, c.reservedUntil = :reservedUntil, c.version = c.version + 1 " +
            "where c.id = :id and (c.status = 'ISSUED' or " +
            "(c.status = 'RESERVED' and c.orderId = :orderId and c.reservationAttemptId < :attemptId))",
    )
    fun reserveIfAvailable(id: Long, orderId: Long, discount: BigDecimal, reservedUntil: Instant, attemptId: Long): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update IssuedCouponJpaEntity c set c.status = 'ISSUED', c.orderId = null, c.discountAmount = null, " +
            "c.reservedUntil = null, c.reservationAttemptId = 0, c.version = c.version + 1 " +
            "where c.id = :id and c.status = 'RESERVED' and c.orderId = :orderId and c.reservationAttemptId = :attemptId",
    )
    fun releaseIfReservedBy(id: Long, orderId: Long, attemptId: Long): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update IssuedCouponJpaEntity c set c.status = 'USED', c.usedAt = :now, c.version = c.version + 1 " +
            "where c.id = :id and c.status = 'RESERVED' and c.orderId = :orderId and c.reservationAttemptId = :attemptId",
    )
    fun confirmIfReservedBy(id: Long, orderId: Long, now: Instant, attemptId: Long): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update IssuedCouponJpaEntity c set c.status = 'REVOKED', c.version = c.version + 1 where c.id = :id and c.status = 'ISSUED'")
    fun revokeIfIssued(id: Long): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update IssuedCouponJpaEntity c set c.status = 'USED', c.orderId = :orderId, c.usedAt = :now, " +
            "c.reservedUntil = null, c.reservationAttemptId = :attemptId, c.version = c.version + 1 " +
            "where c.id = :id and c.status = 'ISSUED' and c.version = :releasedVersion",
    )
    fun restoreReleasedUse(id: Long, orderId: Long, now: Instant, attemptId: Long, releasedVersion: Long): Int
}
