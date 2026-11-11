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
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

/**
 * 결제 결과 처리는 호출자(비동기 이벤트 리스너)의 트랜잭션 하나에서 끝낸다. 별도 REQUIRES_NEW를 열지 않는다.
 * 리스너가 가상 스레드로 무제한 동시에 돌 수 있어, 트랜잭션 안에서 커넥션을 하나 더 잡으면 풀 고갈과 교착이 생기기 때문이다.
 * 단독 호출돼도 트랜잭션이 있도록 기본 전파(REQUIRED)로 감싼다.
 *
 * 좌석 충돌은 ON CONFLICT DO NOTHING이라 트랜잭션이 깨지지 않으므로, 같은 트랜잭션에서 이 예매의 sold_seat를 지우고 취소한다.
 * Redis 반영은 커밋 뒤(afterCommit)에만 한다. 롤백되면 Redis를 건드리지 않는다.
 * 커밋 뒤 Redis 반영이 실패하면 경고만 남긴다. 선점 키는 TTL로 정리되고 스냅샷 보정은 M2 Batch가 한다.
 * 낙관적 락 충돌은 삼키지 않고 던져 이벤트 발행 레지스트리가 다시 시도하게 한다.
 */
@Service
class PaymentResultService(
    private val bookings: BookingCommandPort,
    private val seatHold: SeatHoldPort,
    private val events: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) : HandlePaymentResultUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    private sealed interface After {
        data object Nothing : After
        data class MarkSold(val booking: Booking) : After
        data class Release(val booking: Booking) : After
    }

    override fun onPaymentSucceeded(paymentId: Long, bookingId: Long, amount: BigDecimal) {
        tx.executeWithoutResult {
            applyAfterCommit(confirm(paymentId, BookingId(bookingId), amount), paymentId)
        }
    }

    private fun confirm(paymentId: Long, bookingId: BookingId, amount: BigDecimal): After {
        val booking = bookings.findById(bookingId) ?: return unknownBooking(paymentId, bookingId)
        val now = clock.instant()
        val before = booking.status
        return when (val outcome = booking.onPaymentSucceeded(now, amount)) {
            PaymentOutcome.ConfirmationRequired -> {
                val seatIds = booking.seats.map { it.seatId }

                val inserted = bookings.insertSoldSeats(bookingId, booking.scheduleId, seatIds, now)

                if (inserted == seatIds.size) {
                    booking.confirm(now)
                    val saved = bookings.save(booking)

                    events.publishEvent(
                        BookingConfirmed(bookingId.value, booking.memberId.value, booking.scheduleId.value, seatIds.map { it.value }, booking.totalPrice, now),
                    )
                    log.info("예매 확정 bookingId={} paymentId={}", bookingId.value, paymentId)
                    After.MarkSold(saved)
                } else {
                    bookings.deleteSoldSeats(bookingId)
                    booking.failConfirmation(now)
                    cancelWithRefund(booking, "SEAT_ALREADY_SOLD", now)
                    log.warn("좌석이 이미 판매되어 예매를 취소하고 환불을 요청한다 bookingId={} paymentId={}", bookingId.value, paymentId)
                    After.Release(bookings.save(booking))
                }
            }
            PaymentOutcome.AmountMismatch -> {
                cancelWithRefund(booking, "AMOUNT_MISMATCH", now)
                log.warn("결제 금액이 예매 금액과 달라 예매를 취소하고 환불을 요청한다. bookingId={} paymentId={} paid={} expected={}", bookingId.value, paymentId, amount, booking.totalPrice)
                After.Release(bookings.save(booking))
            }
            PaymentOutcome.AlreadyConfirmed -> {
                log.info("이미 확정된 예매라 무시한다 bookingId={} paymentId={}", bookingId.value, paymentId)
                After.Nothing
            }
            is PaymentOutcome.RefundRequired -> refund(booking, before, outcome, paymentId, now)
        }
    }

    private fun cancelWithRefund(booking: Booking, reason: String, now: Instant) {
        val id = requireNotNull(booking.id).value
        events.publishEvent(RefundRequested(id, reason, now))
        events.publishEvent(BookingCancelled(id, booking.memberId.value, booking.scheduleId.value, reason, true, now))
    }

    private fun refund(booking: Booking, before: BookingStatus, outcome: PaymentOutcome.RefundRequired, paymentId: Long, now: Instant): After {
        val bookingId = requireNotNull(booking.id).value
        val saved = if (booking.status != before) bookings.save(booking) else booking

        events.publishEvent(RefundRequested(bookingId, outcome.reason.name, now))
        if (booking.status != before) {
            events.publishEvent(BookingExpired(bookingId, booking.memberId.value, booking.scheduleId.value, now))
        }

        log.info("결제는 성공했지만 환불을 요청한다 bookingId={} paymentId={} reason={}", bookingId, paymentId, outcome.reason)
        return After.Release(saved)
    }

    override fun onPaymentFailed(paymentId: Long, bookingId: Long, reason: String) {
        tx.executeWithoutResult {
            val booking = bookings.findById(BookingId(bookingId))
            val after = if (booking == null) {
                unknownBooking(paymentId, BookingId(bookingId))
            } else if (booking.status != BookingStatus.PENDING) {
                log.info("PENDING이 아니라 결제 실패를 무시한다 bookingId={} paymentId={} status={}", bookingId, paymentId, booking.status)
                After.Nothing
            } else {
                val now = clock.instant()
                booking.onPaymentFailed(now)
                val saved = bookings.save(booking)
                events.publishEvent(
                    BookingCancelled(bookingId, booking.memberId.value, booking.scheduleId.value, "PAYMENT_FAILED", false, now),
                )
                log.info("결제 실패로 예매 취소 bookingId={} paymentId={} reason={}", bookingId, paymentId, reason)
                After.Release(saved)
            }
            applyAfterCommit(after, paymentId)
        }
    }

    override fun onPaymentRefunded(paymentId: Long, bookingId: Long) {
        log.info("환불 완료 bookingId={} paymentId={}", bookingId, paymentId)
    }

    private fun unknownBooking(paymentId: Long, bookingId: BookingId): After {
        log.warn("결제 결과의 예매를 찾을 수 없다 bookingId={} paymentId={}", bookingId.value, paymentId)
        return After.Nothing
    }

    /** 커밋 뒤에 Redis를 반영한다. 활성 동기화가 없으면 바로 실행한다. */
    private fun applyAfterCommit(after: After, paymentId: Long) {
        if (after == After.Nothing) return
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            applyRedis(after, paymentId)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = applyRedis(after, paymentId)
            },
        )
    }

    private fun applyRedis(after: After, paymentId: Long) {
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
            // DB는 이미 커밋됐다. 남은 선점 키는 TTL이 정리하고 스냅샷은 M2 Batch가 보정한다
            log.warn("커밋 후 Redis {}에 실패했다 bookingId={} paymentId={}", what, booking.id?.value, paymentId, e)
        }
    }
}
