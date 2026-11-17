import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/lib/problem';
import { gateway } from './gateway-client';
import { createSseTicket } from './member';
import { POST as ticketPost } from '@/app/api/streams/tickets/route';
import { POST as claimPost } from '@/app/api/coupons/[definitionId]/claims/route';
import {
  PUT as couponPut,
  DELETE as couponDelete,
} from '@/app/api/bookings/[id]/coupon/route';
import { applyBookingCoupon } from './coupon';
import { POST as bookingPost } from '@/app/api/bookings/route';
vi.mock('./gateway-client', async (original) => {
  const actual = await original<typeof import('./gateway-client')>();
  return { ...actual, gateway: { call: vi.fn() } };
});
vi.mock('./coupon', () => ({
  claimCoupon: vi.fn(),
  applyBookingCoupon: vi.fn(),
  removeBookingCoupon: vi.fn(),
}));
vi.mock('./member', () => ({ createSseTicket: vi.fn() }));
function request(
  path: string,
  body: unknown,
  origin = 'http://localhost:13000',
) {
  return new Request(`http://localhost:13000${path}`, {
    method: 'POST',
    headers: { origin, 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(createSseTicket).mockResolvedValue({
    ticket: 'ticket',
    expiresAt: '2026-01-01',
  });
});
describe('브라우저 API', () => {
  it('다른 Origin의 변경 요청을 거부한다', async () => {
    const response = await bookingPost(
      request('/api/bookings', {}, 'https://evil.test'),
    );
    expect(response.status).toBe(403);
    expect(await response.json()).toEqual({
      status: 403,
      code: 'CSRF_REJECTED',
    });
  });
  it('티켓 응답은 필요한 값만 담고 좌석에만 마지막 ID를 붙인다', async () => {
    for (const kind of ['queue', 'seats']) {
      const response = await ticketPost(
        request('/api/streams/tickets', {
          kind,
          scheduleId: 1,
          lastEventId: '1-0',
        }),
      );
      const data = await response.json();
      expect(Object.keys(data).sort()).toEqual(['expiresAt', 'url']);
      const url = new URL(data.url);
      expect(url.searchParams.get('ticket')).toBe('ticket');
      expect(url.searchParams.get('lastEventId')).toBe(
        kind === 'seats' ? '1-0' : null,
      );
    }
  });
  it('예매 키와 입장 토큰을 헤더 옵션으로 전달한다', async () => {
    vi.mocked(gateway.call).mockResolvedValue({
      bookingId: 1,
      status: 'PENDING',
      totalAmount: 1,
      expiresAt: 'now',
      seats: [],
    });
    const response = await bookingPost(
      request('/api/bookings', {
        scheduleId: 1,
        seatIds: [2],
        admissionToken: 'admission',
        idempotencyKey: 'key',
      }),
    );
    expect(response.status).toBe(200);
    expect(gateway.call).toHaveBeenCalledWith(
      expect.objectContaining({
        headers: { admissionToken: 'admission', idempotencyKey: 'key' },
        body: { scheduleId: 1, seatIds: [2] },
      }),
    );
  });
  it('백엔드 detail을 노출하지 않고 좌석 충돌 ID만 전달한다', async () => {
    vi.mocked(gateway.call).mockRejectedValue(
      new ApiError(409, 'SEAT_ALREADY_HELD', 'secret', [2]),
    );
    const response = await bookingPost(
      request('/api/bookings', {
        scheduleId: 1,
        seatIds: [2],
        admissionToken: 'admission',
        idempotencyKey: 'key',
      }),
    );
    expect(await response.json()).toEqual({
      status: 409,
      code: 'SEAT_ALREADY_HELD',
      seatIds: [2],
    });
  });
});

describe('쿠폰 BFF API', () => {
  const evil = {
    origin: 'https://evil.test',
    'content-type': 'application/json',
  };
  it('다른 Origin의 쿠폰 변경 요청을 거부한다', async () => {
    const id = { params: Promise.resolve({ id: '1' }) };
    const claim = await claimPost(
      new Request('http://localhost:13000/api/coupons/1/claims', {
        method: 'POST',
        headers: evil,
      }),
      { params: Promise.resolve({ definitionId: '1' }) },
    );
    const put = await couponPut(
      new Request('http://localhost:13000/api/bookings/1/coupon', {
        method: 'PUT',
        headers: evil,
        body: JSON.stringify({ issuedCouponId: 1 }),
      }),
      id,
    );
    const del = await couponDelete(
      new Request('http://localhost:13000/api/bookings/1/coupon', {
        method: 'DELETE',
        headers: evil,
      }),
      id,
    );
    expect([claim.status, put.status, del.status]).toEqual([403, 403, 403]);
  });
  it('쿠폰 적용 본문을 검증한다', async () => {
    const response = await couponPut(
      new Request('http://localhost:13000/api/bookings/1/coupon', {
        method: 'PUT',
        headers: {
          origin: 'http://localhost:13000',
          'content-type': 'application/json',
        },
        body: JSON.stringify({ issuedCouponId: 'x' }),
      }),
      { params: Promise.resolve({ id: '1' }) },
    );
    expect(response.status).toBe(400);
    expect(applyBookingCoupon).not.toHaveBeenCalled();
  });
});
