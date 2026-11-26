import { describe, expect, it } from 'vitest';
import {
  zBookingCouponView,
  zCreateBookingRequest,
} from './generated/core/zod.gen';
import { zQueueResponse } from './generated/realtime/zod.gen';

describe('생성된 API 계약', () => {
  it('예매 요청은 JSON 정수와 필수 좌석 목록을 받는다', () => {
    expect(
      zCreateBookingRequest.parse({ scheduleId: 42, seatIds: [12] }),
    ).toEqual({ scheduleId: 42, seatIds: [12] });
    expect(
      zCreateBookingRequest.safeParse({ scheduleId: '42', seatIds: [12] })
        .success,
    ).toBe(false);
    expect(
      zCreateBookingRequest.safeParse({ scheduleId: 0, seatIds: [12] }).success,
    ).toBe(false);
    expect(zCreateBookingRequest.safeParse({ scheduleId: 42 }).success).toBe(
      false,
    );
  });

  it('nullable 필드를 허용하고 필수 상태와 열거형 값을 검증한다', () => {
    expect(
      zBookingCouponView.safeParse({
        issuedCouponId: 12,
        state: 'RESERVING',
        discountAmount: null,
        rejectReason: null,
      }).success,
    ).toBe(true);
    expect(zQueueResponse.safeParse({ position: null }).success).toBe(false);
    expect(
      zQueueResponse.safeParse({ status: 'UNKNOWN', position: null }).success,
    ).toBe(false);
  });
});
