package io.github.ticketing.coupon.application.port.required

import java.time.Instant

/** 고아 예약 정리 Job용 조회. */
interface StaleReservationQueryPort {
    /** 기한이 cutoff보다 앞선 RESERVED 쿠폰 id를 id 오름차순 keyset으로 돌려준다. */
    fun findStaleReservationIds(cutoff: Instant, afterId: Long?, limit: Int): List<Long>
}
