package io.github.ticketing.coupon.application.service

import io.github.ticketing.DomainException
import io.github.ticketing.coupon.domain.COUPON_DEFINITION_LOCKED
import io.github.ticketing.coupon.domain.COUPON_ORDER_MISMATCH
import io.github.ticketing.coupon.domain.COUPON_VALID_UNTIL_SHORTENED
import io.github.ticketing.coupon.domain.CouponRuleException
import io.github.ticketing.coupon.domain.INVALID_COUPON_STATE
import org.springframework.http.HttpStatus

internal class CouponApiException(code: String, status: HttpStatus, message: String) : DomainException(code, status, message)

internal fun couponNotFound() = CouponApiException("COUPON_NOT_FOUND", HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다.")

internal fun couponNotIssuable() = CouponApiException("COUPON_NOT_ISSUABLE", HttpStatus.CONFLICT, "발급할 수 없는 쿠폰입니다.")

internal fun couponAlreadyClaimed() = CouponApiException("COUPON_ALREADY_CLAIMED", HttpStatus.CONFLICT, "이미 받은 쿠폰입니다.")

internal fun couponSoldOut() = CouponApiException("COUPON_SOLD_OUT", HttpStatus.CONFLICT, "선착순 수량이 모두 소진되었습니다.")

internal fun couponNotRevocable() = CouponApiException("COUPON_NOT_REVOCABLE", HttpStatus.CONFLICT, "회수할 수 없는 상태의 쿠폰입니다.")

/** 도메인 규칙 코드별 HTTP 상태를 정하는 한 곳. 새 규칙 코드는 여기에 추가한다(기본값은 409). */
private val RULE_STATUS: Map<String, HttpStatus> = mapOf(
    COUPON_DEFINITION_LOCKED to HttpStatus.CONFLICT,
    COUPON_VALID_UNTIL_SHORTENED to HttpStatus.CONFLICT,
    INVALID_COUPON_STATE to HttpStatus.CONFLICT,
    COUPON_ORDER_MISMATCH to HttpStatus.CONFLICT,
)

/** 도메인이 던진 규칙 위반과 인자 불변식 위반을 [DomainException]으로 바꾼다. */
internal inline fun <T> translateRules(block: () -> T): T = try {
    block()
} catch (e: CouponRuleException) {
    throw CouponApiException(e.code, RULE_STATUS[e.code] ?: HttpStatus.CONFLICT, e.message ?: e.code).apply { initCause(e) }
} catch (e: IllegalArgumentException) {
    throw CouponApiException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, e.message ?: "요청 값이 올바르지 않습니다.").apply { initCause(e) }
}
