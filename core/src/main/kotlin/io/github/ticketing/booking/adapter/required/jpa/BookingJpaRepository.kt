package io.github.ticketing.booking.adapter.required.jpa

import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

internal interface BookingJpaRepository : Repository<BookingJpaEntity, Long> {
    fun <S : BookingJpaEntity> saveAndFlush(entity: S): S

    @Query("select b from BookingJpaEntity b left join fetch b.seats where b.id = :id")
    fun findWithSeatsById(id: Long): BookingJpaEntity?

    @Query("select b from BookingJpaEntity b left join fetch b.seats where b.memberId = :memberId and b.idempotencyKey = :key")
    fun findWithSeatsByMemberIdAndIdempotencyKey(memberId: Long, key: String): BookingJpaEntity?
}
