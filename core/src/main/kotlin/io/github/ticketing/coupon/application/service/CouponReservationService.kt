package io.github.ticketing.coupon.application.service

import io.github.ticketing.coupon.CouponRejected
import io.github.ticketing.coupon.CouponReleaseRequested
import io.github.ticketing.coupon.CouponReservationRequested
import io.github.ticketing.coupon.CouponReserved
import io.github.ticketing.coupon.CouponUseConfirmed
import io.github.ticketing.coupon.application.port.provided.ConfirmCouponUseUseCase
import io.github.ticketing.coupon.application.port.provided.ReleaseCouponUseCase
import io.github.ticketing.coupon.application.port.provided.ReleaseStaleCouponReservationUseCase
import io.github.ticketing.coupon.application.port.provided.ReserveCouponUseCase
import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.application.port.required.IssuedCouponCommandPort
import io.github.ticketing.coupon.application.port.required.UseMismatchPort
import io.github.ticketing.coupon.domain.DiscountResult
import io.github.ticketing.coupon.domain.IssuedCoupon
import io.github.ticketing.coupon.domain.IssuedCouponId
import io.github.ticketing.coupon.domain.IssuedCouponStatus
import io.github.ticketing.coupon.domain.MemberId
import io.github.ticketing.coupon.domain.OrderId
import io.github.ticketing.coupon.domain.PerformanceId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 예매–쿠폰 예약 Saga의 coupon 측. 모든 처리는 (orderId, issuedCouponId) 기준으로 멱등하다. */
@Service
class CouponReservationService(
    private val issuedCoupons: IssuedCouponCommandPort,
    private val definitions: CouponDefinitionCommandPort,
    private val mismatches: UseMismatchPort,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    @Value("\${ticketing.coupon.reservation-ttl:1h}") private val reservationTtl: Duration,
) : ReserveCouponUseCase,
    ReleaseCouponUseCase,
    ConfirmCouponUseUseCase,
    ReleaseStaleCouponReservationUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    private sealed interface Outcome {
        data class Reserved(val discount: BigDecimal) : Outcome

        data class Rejected(val reason: String) : Outcome
    }

    @Transactional
    override fun reserve(request: CouponReservationRequested) {
        val outcome = decide(request, retry = false)
        events.publishEvent(
            when (outcome) {
                is Outcome.Reserved -> CouponReserved(request.orderId, request.issuedCouponId, outcome.discount)
                is Outcome.Rejected -> CouponRejected(request.orderId, request.issuedCouponId, outcome.reason)
            },
        )
    }

    private fun decide(request: CouponReservationRequested, retry: Boolean): Outcome {
        val coupon = issuedCoupons.findById(IssuedCouponId(request.issuedCouponId))
        return earlyOutcome(coupon, request) ?: reserveNew(requireNotNull(coupon), request, retry)
    }

    /** 조건부 UPDATE까지 가지 않고 끝나는 경우(없음·남의 쿠폰·멱등 재전달·사용 불가)의 결과. 예약을 시도해야 하면 null. */
    private fun earlyOutcome(coupon: IssuedCoupon?, request: CouponReservationRequested): Outcome? {
        val reservedDiscount = coupon?.discountAmount
        return when {
            coupon == null -> Outcome.Rejected(NOT_FOUND)

            coupon.memberId != MemberId(request.memberId) -> Outcome.Rejected(NOT_OWNER)

            coupon.status == IssuedCouponStatus.RESERVED && coupon.orderId == OrderId(request.orderId) && reservedDiscount != null ->
                Outcome.Reserved(reservedDiscount)

            coupon.status != IssuedCouponStatus.ISSUED -> Outcome.Rejected(NOT_AVAILABLE)

            else -> null
        }
    }

    private fun reserveNew(coupon: IssuedCoupon, request: CouponReservationRequested, retry: Boolean): Outcome {
        val definition = definitions.findById(coupon.definitionId)
        val now = clock.instant()
        val result = definition?.discountPolicy?.calculate(request.listAmount, PerformanceId(request.performanceId), now)
        return when (result) {
            null -> Outcome.Rejected(NOT_FOUND)

            is DiscountResult.Rejected -> Outcome.Rejected(result.reason.name)

            is DiscountResult.Accepted -> {
                val reserved = issuedCoupons.reserveIfIssued(
                    IssuedCouponId(request.issuedCouponId),
                    OrderId(request.orderId),
                    result.discountAmount,
                    now.plus(reservationTtl),
                )
                when {
                    reserved -> Outcome.Reserved(result.discountAmount)

                    // 경합에서 졌다. 다시 읽어 같은 주문의 선점이면 멱등 응답, 아니면 거부한다
                    !retry -> decide(request, retry = true)

                    else -> Outcome.Rejected(NOT_AVAILABLE)
                }
            }
        }
    }

    @Transactional
    override fun release(request: CouponReleaseRequested) {
        val released = issuedCoupons.releaseIfReservedBy(IssuedCouponId(request.issuedCouponId), OrderId(request.orderId))
        if (!released) {
            log.info("쿠폰 해제 무시: 이미 해제됐거나 다른 주문 예약 또는 사용됨 orderId={} couponId={}", request.orderId, request.issuedCouponId)
        }
    }

    @Transactional
    override fun releaseIfStale(issuedCouponId: Long, cutoff: Instant): Boolean {
        val id = IssuedCouponId(issuedCouponId)
        // orderId는 로그용으로만 읽는다. 되돌릴지는 조건부 UPDATE가 정하므로 그 사이 확정된 쿠폰은 갱신되지 않는다
        val orderId = issuedCoupons.findById(id)?.orderId?.value
        val released = issuedCoupons.releaseIfStale(id, cutoff)
        if (released) log.info("고아 쿠폰 예약 해제 orderId={} couponId={}", orderId, issuedCouponId)
        return released
    }

    @Transactional
    override fun confirm(request: CouponUseConfirmed) {
        val couponId = IssuedCouponId(request.issuedCouponId)
        val orderId = OrderId(request.orderId)
        if (issuedCoupons.confirmIfReservedBy(couponId, orderId, request.confirmedAt)) return

        val current = issuedCoupons.findById(couponId)
        if (current == null) {
            log.warn("쿠폰 사용 확정 대상 없음 orderId={} couponId={}", request.orderId, request.issuedCouponId)
        } else if (current.status != IssuedCouponStatus.USED || current.orderId != orderId) {
            forceUse(current, couponId, orderId, request)
        }
    }

    /** 결제가 이미 끝났으므로 거부하지 않고 USED로 맞춘 뒤 불일치를 남긴다. */
    private fun forceUse(current: IssuedCoupon, couponId: IssuedCouponId, orderId: OrderId, request: CouponUseConfirmed) {
        val previous = issuedCoupons.forceUse(couponId, orderId, request.confirmedAt) ?: return
        val detail = "사용 확정이 RESERVED(같은 주문)가 아닌 상태에 도착: previousOrderId=${current.orderId?.value}"
        mismatches.record(couponId, orderId, previous, clock.instant(), detail)
        log.warn("쿠폰 사용 불일치 강제 USED orderId={} couponId={} previous={}", request.orderId, request.issuedCouponId, previous)
    }

    private companion object {
        const val NOT_FOUND = "NOT_FOUND"
        const val NOT_OWNER = "NOT_OWNER"
        const val NOT_AVAILABLE = "NOT_AVAILABLE"
    }
}
