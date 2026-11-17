package io.github.ticketing.coupon.application.port.provided

import io.github.ticketing.coupon.CouponReleaseRequested
import io.github.ticketing.coupon.CouponReservationRequested
import io.github.ticketing.coupon.CouponUseConfirmed
import java.time.Instant

interface ReserveCouponUseCase {
    /** 예약 결과는 CouponReserved 또는 CouponRejected 이벤트로 발행한다. */
    fun reserve(request: CouponReservationRequested)
}

interface ReleaseCouponUseCase {
    fun release(request: CouponReleaseRequested)
}

interface ReleaseStaleCouponReservationUseCase {
    /** 기한이 cutoff보다 앞선 RESERVED 쿠폰을 ISSUED로 되돌린다. 되돌렸으면 true. */
    fun releaseIfStale(issuedCouponId: Long, cutoff: Instant): Boolean
}

interface ConfirmCouponUseUseCase {
    fun confirm(request: CouponUseConfirmed)
}

interface ReconcileCouponStockUseCase {
    /**
     * 선착순 쿠폰의 Redis 재고·회원 Set을 DB 기준으로 보수적으로만 고치고 바꾼 건수를 돌려준다.
     * redis 전략이 아니면 아무것도 하지 않는다(0).
     * 재고는 낮추기만 하므로 보상 실패로 Redis가 DB보다 작아진 누수 재고는 복구되지 않는다.
     */
    fun reconcile(definitionId: Long): Int
}
