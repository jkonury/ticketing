export function bookingError(code: string): string {
  switch (code) {
    case 'SEAT_ALREADY_HELD':
      return '이미 선점된 좌석입니다. 다시 선택해 주세요.';
    case 'PERFORMANCE_SUSPENDED':
      return '판매가 중지된 공연입니다.';
    case 'PERFORMANCE_CANCELLED':
      return '취소된 공연은 예매하거나 결제할 수 없어요.';
    case 'BOOKING_EXPIRED':
      return '선점이 만료되었습니다.';
    case 'ADMISSION_TOKEN_REQUIRED':
    case 'ADMISSION_TOKEN_INVALID':
      return '입장이 만료되어 대기열로 돌아갑니다.';
    case 'RATE_LIMITED':
      return '요청이 많습니다. 잠시 후 다시 시도해 주세요.';
    case 'INVALID_BOOKING_STATE':
      return '현재 상태에서는 처리할 수 없습니다.';
    case 'TOO_MANY_SEATS':
      return '좌석은 최대 4개까지 선택할 수 있습니다.';
    case 'COUPON_PENDING':
      return '쿠폰 적용을 확인하는 중입니다. 잠시 후 다시 시도해 주세요.';
    case 'COUPON_CHANGE_NOT_ALLOWED':
      return '현재 상태에서는 쿠폰을 바꿀 수 없습니다.';
    case 'SESSION_EXPIRED':
      return '로그인이 만료되었습니다.';
    default:
      return '요청을 처리하지 못했습니다. 다시 시도해 주세요.';
  }
}

const rejectReasons: Record<string, string> = {
  NOT_FOUND: '쿠폰을 찾을 수 없습니다.',
  NOT_OWNER: '본인의 쿠폰이 아닙니다.',
  NOT_AVAILABLE: '사용할 수 없는 쿠폰입니다.',
  OUT_OF_VALID_PERIOD: '쿠폰 유효 기간이 아닙니다.',
  PERFORMANCE_MISMATCH: '이 공연에 사용할 수 없는 쿠폰입니다.',
  BELOW_MIN_ORDER_AMOUNT: '최소 주문 금액에 못 미칩니다.',
  AMOUNT_BELOW_MINIMUM: '할인 후 금액이 최소 결제 금액보다 작습니다.',
};
export function couponRejectReason(reason: string | null | undefined) {
  return (reason && rejectReasons[reason]) || '쿠폰을 적용할 수 없습니다.';
}
export function couponError(code: string): string {
  switch (code) {
    case 'COUPON_ALREADY_CLAIMED':
      return '이미 받은 쿠폰입니다.';
    case 'COUPON_NOT_ISSUABLE':
      return '지금은 받을 수 없는 쿠폰입니다.';
    case 'COUPON_NOT_FOUND':
      return '쿠폰을 찾을 수 없습니다.';
    default:
      return bookingError(code);
  }
}
