package io.github.ticketing.coupon.domain

/** 도메인 규칙 위반. HTTP 상태 변환은 application 계층이 맡는다. */
open class CouponRuleException(val code: String, message: String = code) : RuntimeException(message)

class DefinitionLockedException(field: String) : CouponRuleException(COUPON_DEFINITION_LOCKED, "$COUPON_DEFINITION_LOCKED: $field")

class ValidUntilShortenedException : CouponRuleException(COUPON_VALID_UNTIL_SHORTENED)

class InvalidCouponStateException(status: IssuedCouponStatus) :
    CouponRuleException(INVALID_COUPON_STATE, "$INVALID_COUPON_STATE: $status")

class CouponOrderMismatchException : CouponRuleException(COUPON_ORDER_MISMATCH)

const val COUPON_DEFINITION_LOCKED = "COUPON_DEFINITION_LOCKED"
const val COUPON_VALID_UNTIL_SHORTENED = "COUPON_VALID_UNTIL_SHORTENED"
const val INVALID_COUPON_STATE = "INVALID_COUPON_STATE"
const val COUPON_ORDER_MISMATCH = "COUPON_ORDER_MISMATCH"
