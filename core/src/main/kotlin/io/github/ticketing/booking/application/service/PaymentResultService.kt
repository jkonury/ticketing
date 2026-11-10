package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
import io.github.ticketing.booking.BookingExpired
import io.github.ticketing.booking.application.port.provided.HandlePaymentResultUseCase
import io.github.ticketing.booking.application.port.required.BookingCommandPort
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.BookingId
import io.github.ticketing.booking.domain.BookingStatus
import io.github.ticketing.booking.domain.PaymentOutcome
import io.github.ticketing.payment.RefundRequested
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock

/**
 * 호출자(이벤트 리스너)가 이미 트랜잭션 안에 있어도 DB 반영은 별도 트랜잭션으로 커밋한다.
 * Redis 반영은 항상 그 커밋 뒤에 한다. 커밋이 실패했는데 Redis만 SOLD가 되면 팔리지 않은 좌석이 판매 불가로 남는다.
 * 커밋 뒤 Redis 반영이 실패해도 DB 유니크 제약이 중복 판매를 막고 선점 키는 TTL로 정리된다.
 * 낙관적 락 충돌은 그대로 던져 이벤트 발행 레지스트리가 다시 시도하게 한다.
 */
@Service
class PaymentResultService(
    private val bookings: BookingCommandPort,
    private val seatHold: SeatHoldPort,
    private val events: ApplicationEventPublisher,
    txManager: PlatformTransactionManager,
    private val clock: Clock,
) : HandlePaymentResultUseCase {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    private sealed interface After {
        data object Nothing : After
        data class MarkSold(val booking: Booking) : After
        data class Release(val booking: Booking) : After
    }

    /** 유니크 제약에 걸려 좌석 일부 또는 전부가 이미 판매된 경우. 트랜잭션을 롤백시키기 위한 신호다. */
    private class SeatAlreadySoldSignal : RuntimeException()

    override fun onPaymentSucceeded(paymentId: Long, bookingId: Long, amount: BigDecimal) {
        val after = try {
            requireNotNull(tx.execute { confirm(paymentId, BookingId(bookingId), amount) })
        } catch (_: SeatAlreadySoldSignal) {
            requireNotNull(tx.execute { failConfirmation(paymentId, BookingId(bookingId)) })
        }
        applyAfterCommit(after, paymentId)
    }

    private fun confirm(paymentId: Long, bookingId: BookingId, amount: BigDecimal): After {
        val booking = bookings.findById(bookingId) ?: return unknownBooking(paymentId, bookingId)
        val now = clock.instant()
        val before = booking.status
        return when (val outcome = booking.onPaymentSucceeded(now)) {
            PaymentOutcome.Confirmed -> {
                if (amount.compareTo(booking.totalPrice) != 0) {
                    log.warn("결제 금액이 예매 금액과 다르다. bookingId={} paymentId={} paid={} expected={}", bookingId.value, paymentId, amount, booking.totalPrice)
                }
                val seatIds = booking.seats.map { it.seatId }
                val inserted = bookings.insertSoldSeats(bookingId, booking.scheduleId, seatIds, now)
                if (inserted != seatIds.size) throw SeatAlreadySoldSignal()
                val saved = bookings.save(booking)
                events.publishEvent(
                    BookingConfirmed(bookingId.value, booking.memberId.value, booking.scheduleId.value, seatIds.map { it.value }, booking.totalPrice, now),
                )
                log.info("예매 확정 bookingId={} paymentId={}", bookingId.value, paymentId)
                After.MarkSold(saved)
            }
            PaymentOutcome.AlreadyConfirmed -> {
                log.info("이미 확정된 예매라 무시한다 bookingId={} paymentId={}", bookingId.value, paymentId)
                After.Nothing
            }
            is PaymentOutcome.RefundRequired -> refund(booking, before, outcome, paymentId, now)
        }
    }

    private fun refund(booking: Booking, before: BookingStatus, outcome: PaymentOutcome.RefundRequired, paymentId: Long, now: java.time.Instant): After {
        val bookingId = requireNotNull(booking.id).value
        val saved = if (booking.status != before) bookings.save(booking) else booking
        events.publishEvent(RefundRequested(bookingId, outcome.reason.name, now))
        if (booking.status != before) {
            events.publishEvent(BookingExpired(bookingId, booking.memberId.value, booking.scheduleId.value, now))
        }
        log.info("결제는 성공했지만 환불을 요청한다 bookingId={} paymentId={} reason={}", bookingId, paymentId, outcome.reason)
        return After.Release(saved)
    }

    private fun failConfirmation(paymentId: Long, bookingId: BookingId): After {
        val booking = bookings.findById(bookingId) ?: return unknownBooking(paymentId, bookingId)
        val now = clock.instant()
        if (booking.status != BookingStatus.PENDING) {
            // 롤백 사이에 다른 경로가 상태를 바꿨다. 다시 판정한다
            return when (val outcome = booking.onPaymentSucceeded(now)) {
                PaymentOutcome.Confirmed, PaymentOutcome.AlreadyConfirmed -> After.Nothing
                is PaymentOutcome.RefundRequired -> refund(booking, booking.status, outcome, paymentId, now)
            }
        }
        booking.failConfirmation(now)
        val saved = bookings.save(booking)
        events.publishEvent(RefundRequested(bookingId.value, "SEAT_ALREADY_SOLD", now))
        events.publishEvent(
            BookingCancelled(bookingId.value, booking.memberId.value, booking.scheduleId.value, "SEAT_ALREADY_SOLD", true, now),
        )
        log.warn("좌석이 이미 판매되어 예매를 취소하고 환불을 요청한다 bookingId={} paymentId={}", bookingId.value, paymentId)
        return After.Release(saved)
    }

    override fun onPaymentFailed(paymentId: Long, bookingId: Long, reason: String) {
        val after = requireNotNull(
            tx.execute {
                val booking = bookings.findById(BookingId(bookingId)) ?: return@execute unknownBooking(paymentId, BookingId(bookingId))
                if (booking.status != BookingStatus.PENDING) {
                    log.info("PENDING이 아니라 결제 실패를 무시한다 bookingId={} paymentId={} status={}", bookingId, paymentId, booking.status)
                    return@execute After.Nothing
                }
                val now = clock.instant()
                booking.onPaymentFailed(now)
                val saved = bookings.save(booking)
                events.publishEvent(
                    BookingCancelled(bookingId, booking.memberId.value, booking.scheduleId.value, "PAYMENT_FAILED", false, now),
                )
                log.info("결제 실패로 예매 취소 bookingId={} paymentId={} reason={}", bookingId, paymentId, reason)
                After.Release(saved)
            },
        )
        applyAfterCommit(after, paymentId)
    }

    override fun onPaymentRefunded(paymentId: Long, bookingId: Long) {
        log.info("환불 완료 bookingId={} paymentId={}", bookingId, paymentId)
    }

    private fun unknownBooking(paymentId: Long, bookingId: BookingId): After {
        log.warn("결제 결과의 예매를 찾을 수 없다 bookingId={} paymentId={}", bookingId.value, paymentId)
        return After.Nothing
    }

    private fun applyAfterCommit(after: After, paymentId: Long) {
        when (after) {
            After.Nothing -> Unit
            is After.MarkSold -> redis(after.booking, paymentId, "SOLD 반영") {
                seatHold.markSold(it.scheduleId, it.seats.map { s -> s.seatId }, it.holdToken, clock.instant())
            }
            is After.Release -> redis(after.booking, paymentId, "선점 해제") {
                seatHold.release(it.scheduleId, it.seats.map { s -> s.seatId }, it.holdToken, clock.instant())
            }
        }
    }

    private inline fun redis(booking: Booking, paymentId: Long, what: String, block: (Booking) -> Unit) {
        try {
            block(booking)
        } catch (e: Exception) {
            // DB는 이미 커밋됐다. 남은 선점 키는 TTL이 정리하고 Batch가 보정한다
            log.warn("커밋 후 Redis {}에 실패했다 bookingId={} paymentId={}", what, booking.id?.value, paymentId, e)
        }
    }
}
