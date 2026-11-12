export function bookingError(code: string): string {
  switch (code) {
    case 'SEAT_ALREADY_HELD':
      return '이미 선점된 좌석입니다. 다시 선택해 주세요.';
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
    case 'SESSION_EXPIRED':
      return '로그인이 만료되었습니다.';
    default:
      return '요청을 처리하지 못했습니다. 다시 시도해 주세요.';
  }
}
