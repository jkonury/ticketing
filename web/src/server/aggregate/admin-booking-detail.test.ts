import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/lib/problem';
import { getAdminBooking, getAdminPayment } from '@/server/api/admin';
import { getAdminBookingDetail } from './admin-booking-detail';

vi.mock('@/server/api/admin', () => ({
  getAdminBooking: vi.fn(),
  getAdminPayment: vi.fn(),
}));

const date = '2026-11-28T15:30:00Z';
const booking = {
  bookingId: 8,
  memberId: 3,
  scheduleId: 4,
  status: 'CONFIRMED',
  performanceTitle: '공연',
  startsAt: date,
  expiresAt: date,
  createdAt: date,
  listAmount: 1000,
  payableAmount: 900,
  discountAmount: 100,
  coupon: null,
  seats: [],
} satisfies Awaited<ReturnType<typeof getAdminBooking>>;

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getAdminBooking).mockResolvedValue(booking);
});

describe('관리자 예매 상세 조합', () => {
  it('결제 조회 결과를 예매와 함께 반환한다', async () => {
    const payment = {
      bookingId: 8,
      status: 'SUCCEEDED',
      amount: 900,
      requestedAt: date,
    };
    vi.mocked(getAdminPayment).mockResolvedValue([payment]);
    await expect(getAdminBookingDetail(8)).resolves.toEqual({
      booking,
      payment: { status: 'ok', data: payment },
    });
    expect(getAdminPayment).toHaveBeenCalledExactlyOnceWith(8);
  });

  it('빈 결제 응답을 결제 없음으로 반환한다', async () => {
    vi.mocked(getAdminPayment).mockResolvedValue([]);
    await expect(getAdminBookingDetail(8)).resolves.toEqual({
      booking,
      payment: { status: 'ok', data: null },
    });
  });

  it('결제 장애 시 예매 정보는 그대로 반환한다', async () => {
    vi.mocked(getAdminPayment).mockRejectedValue(
      new ApiError(504, 'UPSTREAM_TIMEOUT'),
    );
    await expect(getAdminBookingDetail(8)).resolves.toEqual({
      booking,
      payment: { status: 'unavailable' },
    });
  });

  it.each([401, 403])(
    '결제 권한 오류 %s를 호출부에 전파한다',
    async (status) => {
      vi.mocked(getAdminPayment).mockRejectedValue(new ApiError(status));
      await expect(getAdminBookingDetail(8)).rejects.toMatchObject({ status });
    },
  );

  it('예매가 없으면 그 예외를 전파하고 결제는 조회하지 않는다', async () => {
    vi.mocked(getAdminBooking).mockRejectedValue(
      new ApiError(404, 'BOOKING_NOT_FOUND'),
    );
    await expect(getAdminBookingDetail(8)).rejects.toMatchObject({
      status: 404,
    });
    expect(getAdminPayment).not.toHaveBeenCalled();
  });
});
