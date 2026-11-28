package io.github.ticketing.booking.application.service

import io.github.ticketing.DomainException
import io.github.ticketing.booking.domain.BOOKING_EXPIRED
import io.github.ticketing.booking.domain.BookingRuleException
import io.github.ticketing.booking.domain.COUPON_CHANGE_NOT_ALLOWED
import io.github.ticketing.booking.domain.COUPON_PENDING
import io.github.ticketing.booking.domain.DUPLICATE_SEAT
import io.github.ticketing.booking.domain.INVALID_BOOKING_STATE
import io.github.ticketing.booking.domain.NOT_BOOKING_OWNER
import io.github.ticketing.booking.domain.NO_SEATS
import io.github.ticketing.booking.domain.SALES_CLOSED
import io.github.ticketing.booking.domain.TOO_MANY_SEATS
import org.springframework.http.HttpStatus

class BookingNotFoundException : DomainException("BOOKING_NOT_FOUND", HttpStatus.NOT_FOUND, "예매를 찾을 수 없습니다.")

class InvalidBookingPeriodException : DomainException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "조회 시작 시각은 종료 시각 이하여야 합니다.")

class ScheduleNotFoundException : DomainException("SCHEDULE_NOT_FOUND", HttpStatus.NOT_FOUND, "회차를 찾을 수 없습니다.")

class ScheduleSaleSuspendedException : DomainException("PERFORMANCE_SUSPENDED", HttpStatus.CONFLICT, "판매가 중지된 공연입니다.")

class SeatNotFoundException : DomainException("SEAT_NOT_FOUND", HttpStatus.NOT_FOUND, "회차에 없는 좌석입니다.")

class SeatAlreadyHeldException(private val seatIds: List<Long>) :
    DomainException("SEAT_ALREADY_HELD", HttpStatus.CONFLICT, "이미 선점되었거나 판매된 좌석이 있습니다.") {
    override val properties: Map<String, Any> get() = mapOf("seatIds" to seatIds)
}

class BookingInProgressException : DomainException("BOOKING_IN_PROGRESS", HttpStatus.CONFLICT, "예매 요청이 처리 중입니다. 같은 키로 다시 시도해 주세요.")

/** 도메인 규칙 예외를 HTTP 상태가 있는 예외로 바꾸는 유일한 지점이다. */
internal class BookingRuleViolation(code: String, status: HttpStatus, message: String) : DomainException(code, status, message)

internal fun BookingRuleException.toApplicationException(): DomainException {
    val status = when (code) {
        TOO_MANY_SEATS, NO_SEATS, DUPLICATE_SEAT -> HttpStatus.BAD_REQUEST
        NOT_BOOKING_OWNER -> HttpStatus.FORBIDDEN
        INVALID_BOOKING_STATE, BOOKING_EXPIRED, SALES_CLOSED, COUPON_PENDING, COUPON_CHANGE_NOT_ALLOWED -> HttpStatus.CONFLICT
        else -> HttpStatus.BAD_REQUEST
    }
    return BookingRuleViolation(code, status, message ?: code)
}

internal inline fun <T> translatingRuleViolations(block: () -> T): T = try {
    block()
} catch (e: BookingRuleException) {
    throw e.toApplicationException()
}
