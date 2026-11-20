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
import io.github.ticketing.coupon.application.port.required.ReservationAttempt
import io.github.ticketing.coupon.application.port.required.ReservationAttemptPort
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

@Service
class CouponReservationService(
    private val issuedCoupons: IssuedCouponCommandPort,
    private val definitions: CouponDefinitionCommandPort,
    private val attempts: ReservationAttemptPort,
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
        val couponId = IssuedCouponId(request.issuedCouponId)
        val orderId = OrderId(request.orderId)
        val attemptId = request.reservationAttemptId
        val coupon = issuedCoupons.findForUpdate(couponId)
        val outcome = when {
            coupon == null -> Outcome.Rejected(NOT_FOUND)
            coupon.memberId != MemberId(request.memberId) -> Outcome.Rejected(NOT_OWNER)
            else -> reserveForOwner(coupon, couponId, orderId, request)
        }
        events.publishEvent(
            when (outcome) {
                is Outcome.Reserved -> CouponReserved(request.orderId, request.issuedCouponId, outcome.discount, attemptId)
                is Outcome.Rejected -> CouponRejected(request.orderId, request.issuedCouponId, outcome.reason, attemptId)
            },
        )
    }

    private fun reserveForOwner(
        coupon: IssuedCoupon,
        couponId: IssuedCouponId,
        orderId: OrderId,
        request: CouponReservationRequested,
    ): Outcome {
        val attemptId = request.reservationAttemptId
        val latest = attempts.find(couponId, orderId)
        val reservedDiscount = coupon.discountAmount
        return when {
            latest != null && latest.id > attemptId -> Outcome.Rejected(NOT_AVAILABLE)

            latest?.id == attemptId && latest.rejectReason != null -> Outcome.Rejected(latest.rejectReason)

            coupon.isReservedBy(orderId, attemptId) && reservedDiscount != null -> Outcome.Reserved(reservedDiscount)

            latest?.id == attemptId -> Outcome.Rejected(NOT_AVAILABLE)

            else -> {
                val outcome = reserveNew(coupon, couponId, orderId, request)
                attempts.save(couponId, orderId, ReservationAttempt(attemptId, (outcome as? Outcome.Rejected)?.reason))
                outcome
            }
        }
    }

    private fun reserveNew(
        coupon: IssuedCoupon,
        couponId: IssuedCouponId,
        orderId: OrderId,
        request: CouponReservationRequested,
    ): Outcome {
        val attemptId = request.reservationAttemptId
        val replacesPriorAttempt = coupon.status == IssuedCouponStatus.RESERVED && coupon.orderId == orderId &&
            coupon.reservationAttemptId < attemptId
        if (coupon.status != IssuedCouponStatus.ISSUED && !replacesPriorAttempt) return Outcome.Rejected(NOT_AVAILABLE)

        val policy = definitions.findById(coupon.definitionId)?.discountPolicy
        val now = clock.instant()
        return when (val result = policy?.calculate(request.listAmount, PerformanceId(request.performanceId), now)) {
            null -> Outcome.Rejected(NOT_FOUND)

            is DiscountResult.Rejected -> Outcome.Rejected(result.reason.name)

            is DiscountResult.Accepted -> {
                check(issuedCoupons.reserveIfAvailable(couponId, orderId, result.discountAmount, now.plus(reservationTtl), attemptId))
                Outcome.Reserved(result.discountAmount)
            }
        }
    }

    @Transactional
    override fun release(request: CouponReleaseRequested) {
        val couponId = IssuedCouponId(request.issuedCouponId)
        val orderId = OrderId(request.orderId)
        val attemptId = request.reservationAttemptId
        val coupon = issuedCoupons.findForUpdate(couponId) ?: return
        if (coupon.isUsedBy(orderId, attemptId)) return

        val released = issuedCoupons.releaseIfReservedBy(couponId, orderId, attemptId)
        val latest = attempts.find(couponId, orderId)
        val closed = latest?.takeIf { it.id == attemptId && it.rejectReason != null }
        val legacyExpiry = attemptId == 0L && request.reason == "EXPIRED"
        val invalidatesRecovery = closed?.releasedVersion != null && !legacyExpiry
        if (released || closed == null || invalidatesRecovery) {
            val releasedVersion = (coupon.version + 1).takeIf { released && legacyExpiry }
            attempts.save(couponId, orderId, ReservationAttempt(attemptId, NOT_AVAILABLE, releasedVersion))
        }
    }

    @Transactional
    override fun releaseIfStale(issuedCouponId: Long, cutoff: Instant): Boolean {
        val couponId = IssuedCouponId(issuedCouponId)
        val coupon = issuedCoupons.findForUpdate(couponId) ?: return false
        val orderId = coupon.orderId
        val expired = coupon.reservedUntil?.isBefore(cutoff) == true
        return if (coupon.status == IssuedCouponStatus.RESERVED && expired && orderId != null) {
            val attemptId = coupon.reservationAttemptId
            check(issuedCoupons.releaseIfReservedBy(couponId, orderId, attemptId))
            attempts.save(couponId, orderId, ReservationAttempt(attemptId, NOT_AVAILABLE, coupon.version + 1))
            log.info("고아 쿠폰 예약 해제 orderId={} couponId={} attemptId={}", orderId.value, issuedCouponId, attemptId)
            true
        } else {
            false
        }
    }

    @Transactional
    override fun confirm(request: CouponUseConfirmed) {
        val couponId = IssuedCouponId(request.issuedCouponId)
        val orderId = OrderId(request.orderId)
        val attemptId = request.reservationAttemptId
        val coupon = issuedCoupons.findForUpdate(couponId) ?: return
        if (coupon.isUsedBy(orderId, attemptId)) return

        val latest = attempts.find(couponId, orderId)
        if (latest == null || latest.id <= attemptId) {
            if (!issuedCoupons.confirmIfReservedBy(couponId, orderId, request.confirmedAt, attemptId)) {
                restoreReleasedUse(coupon, couponId, orderId, latest, request)
            }
        }
    }

    private fun restoreReleasedUse(
        coupon: IssuedCoupon,
        couponId: IssuedCouponId,
        orderId: OrderId,
        latest: ReservationAttempt?,
        request: CouponUseConfirmed,
    ) {
        val attemptId = request.reservationAttemptId
        val releasedVersion = when {
            latest?.id == attemptId -> latest.releasedVersion
            attemptId == 0L && !attempts.exists(couponId) -> coupon.version
            else -> null
        }
        if (releasedVersion != null &&
            issuedCoupons.restoreReleasedUse(couponId, orderId, request.confirmedAt, attemptId, releasedVersion)
        ) {
            attempts.save(couponId, orderId, ReservationAttempt(attemptId))
            val detail = "해제된 시도의 사용 확정: reservationAttemptId=$attemptId"
            mismatches.record(couponId, orderId, coupon.status, clock.instant(), detail)
            log.warn("해제된 쿠폰 사용 확정 orderId={} couponId={} attemptId={}", request.orderId, request.issuedCouponId, attemptId)
        } else {
            log.warn("현재 쿠폰에 반영할 수 없는 사용 확정 orderId={} couponId={} attemptId={}", request.orderId, request.issuedCouponId, attemptId)
        }
    }

    private fun IssuedCoupon.isReservedBy(orderId: OrderId, attemptId: Long): Boolean =
        status == IssuedCouponStatus.RESERVED && this.orderId == orderId && reservationAttemptId == attemptId

    private fun IssuedCoupon.isUsedBy(orderId: OrderId, attemptId: Long): Boolean =
        status == IssuedCouponStatus.USED && this.orderId == orderId && reservationAttemptId == attemptId

    private companion object {
        const val NOT_FOUND = "NOT_FOUND"
        const val NOT_OWNER = "NOT_OWNER"
        const val NOT_AVAILABLE = "NOT_AVAILABLE"
    }
}
