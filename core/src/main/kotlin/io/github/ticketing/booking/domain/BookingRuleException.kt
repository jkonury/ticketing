package io.github.ticketing.booking.domain

/** 도메인 규칙 위반. HTTP 상태 변환은 application 계층이 맡는다. */
open class BookingRuleException(val code: String, message: String = code) : RuntimeException(message)

class TooManySeatsException : BookingRuleException(TOO_MANY_SEATS)

class NoSeatsException : BookingRuleException(NO_SEATS)

class DuplicateSeatException : BookingRuleException(DUPLICATE_SEAT)

class BookingExpiredException : BookingRuleException(BOOKING_EXPIRED)

class InvalidBookingStateException(status: BookingStatus) :
    BookingRuleException(INVALID_BOOKING_STATE, "$INVALID_BOOKING_STATE: $status")

class NotBookingOwnerException : BookingRuleException(NOT_BOOKING_OWNER)

const val TOO_MANY_SEATS = "TOO_MANY_SEATS"
const val NO_SEATS = "NO_SEATS"
const val DUPLICATE_SEAT = "DUPLICATE_SEAT"
const val BOOKING_EXPIRED = "BOOKING_EXPIRED"
const val INVALID_BOOKING_STATE = "INVALID_BOOKING_STATE"
const val NOT_BOOKING_OWNER = "NOT_BOOKING_OWNER"

class CouponPendingException : BookingRuleException(COUPON_PENDING)

class CouponChangeNotAllowedException : BookingRuleException(COUPON_CHANGE_NOT_ALLOWED)

class InvalidCouponDiscountException : BookingRuleException(INVALID_COUPON_DISCOUNT)

const val COUPON_PENDING = "COUPON_PENDING"
const val COUPON_CHANGE_NOT_ALLOWED = "COUPON_CHANGE_NOT_ALLOWED"
const val INVALID_COUPON_DISCOUNT = "INVALID_COUPON_DISCOUNT"
