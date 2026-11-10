package io.github.ticketing.booking.domain

enum class BookingStatus { PENDING, CONFIRMED, EXPIRED, CANCELLED }

enum class CancelReason { USER_REQUESTED, PAYMENT_FAILED, SEAT_ALREADY_SOLD, AMOUNT_MISMATCH }

/** 결제는 성공했지만 환불해야 하는 이유. */
enum class RefundReason { BOOKING_EXPIRED, BOOKING_CANCELLED }

sealed interface PaymentOutcome {
    /** 확정할 수 있다. 상태는 아직 바뀌지 않았다. */
    data object ConfirmationRequired : PaymentOutcome

    /** 결제 금액이 예매 금액과 달라 CANCELLED로 바꿨다. 환불이 필요하다. */
    data object AmountMismatch : PaymentOutcome

    data object AlreadyConfirmed : PaymentOutcome
    data class RefundRequired(val reason: RefundReason) : PaymentOutcome
}

sealed interface CancelOutcome {
    /** PENDING 취소. 선점 좌석을 반환해야 한다. */
    data object ReleasedHold : CancelOutcome

    /** CONFIRMED 취소. 환불을 요청해야 한다. */
    data object RefundRequired : CancelOutcome
}
