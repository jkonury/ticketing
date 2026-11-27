import { describe, expect, it } from 'vitest';
import { performanceSchema } from './catalog';
import {
  zBookingCouponView,
  zCreateBookingRequest,
} from './generated/core/zod.gen';
import { zQueueResponse } from './generated/realtime/zod.gen';

describe('생성된 API 계약', () => {
  it('기존 공연의 null 장르를 허용하고 포스터 URL은 HTTP(S)로 제한한다', () => {
    const performance = {
      id: 1,
      title: '공연',
      description: '',
      venueId: 2,
      venueName: '공연장',
      prices: [],
      schedules: [],
      genre: null,
      posterUrl: null,
    };
    expect(performanceSchema.parse(performance)).toEqual(performance);
    expect(
      performanceSchema.safeParse({
        ...performance,
        posterUrl: 'ftp://example.com/poster.webp',
      }).success,
    ).toBe(false);
  });

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
