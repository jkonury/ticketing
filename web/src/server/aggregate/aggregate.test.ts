import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/lib/problem';
import { SessionExpiredError } from '@/server/api/gateway-client';
import { getPerformance } from '@/server/api/catalog';
import { getAvailability, getBookings, getSales } from '@/server/api/booking';
import { listMyCoupons } from '@/server/api/coupon';
import { getPayments } from '@/server/api/payment';
import { getSettlements } from '@/server/api/settlement';
import { getCurrentUser } from '@/server/auth/current-user';
import { getPerformanceDetail } from './performance-detail';
import { getMyPage } from './my-page';
import { getOrganizerDashboard } from './organizer-dashboard';
vi.mock('@/server/api/catalog', () => ({ getPerformance: vi.fn() }));
vi.mock('@/server/api/booking', () => ({
  getAvailability: vi.fn(),
  getBookings: vi.fn(),
  getSales: vi.fn(),
}));
vi.mock('@/server/api/coupon', () => ({ listMyCoupons: vi.fn() }));
vi.mock('@/server/api/payment', () => ({ getPayments: vi.fn() }));
vi.mock('@/server/api/settlement', () => ({ getSettlements: vi.fn() }));
vi.mock('@/server/auth/current-user', () => ({ getCurrentUser: vi.fn() }));
const performance = {
  id: 1,
  title: '공연',
  description: '',
  venueId: 2,
  venueName: '홀',
  prices: [],
  schedules: [{ id: 3, startsAt: '2026-11-12T00:00:00Z' }],
};
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getPerformance).mockResolvedValue(performance);
  vi.mocked(getCurrentUser).mockResolvedValue({
    memberId: 1,
    name: '회원',
    role: 'USER',
  });
  vi.mocked(getAvailability).mockResolvedValue([]);
  vi.mocked(getBookings).mockResolvedValue({ items: [], nextCursor: null });
  vi.mocked(getPayments).mockResolvedValue([]);
  vi.mocked(listMyCoupons).mockResolvedValue([]);
  vi.mocked(getSales).mockResolvedValue([]);
  vi.mocked(getSettlements).mockResolvedValue([]);
});
describe('화면 조합', () => {
  it('공연 핵심 오류를 그대로 전파한다', async () => {
    const error = new ApiError(404, 'PERFORMANCE_NOT_FOUND');
    vi.mocked(getPerformance).mockRejectedValue(error);
    await expect(getPerformanceDetail(1)).rejects.toBe(error);
  });
  it('부가 타임아웃만 unavailable로 바꾸고 인증 만료는 전파한다', async () => {
    vi.mocked(getAvailability).mockRejectedValue(
      new ApiError(504, 'UPSTREAM_TIMEOUT'),
    );
    const result = await getPerformanceDetail(1);
    expect(result.availability.status).toBe('unavailable');
    expect(result.mine?.status).toBe('ok');
    vi.mocked(getBookings).mockRejectedValue(new SessionExpiredError());
    await expect(getPerformanceDetail(1)).rejects.toBeInstanceOf(
      SessionExpiredError,
    );
  });
  it('비로그인 시 내 예매 호출을 건너뛴다', async () => {
    vi.mocked(getCurrentUser).mockResolvedValue(null);
    expect((await getPerformanceDetail(1)).mine).toBeNull();
    expect(getBookings).not.toHaveBeenCalled();
  });
  it('공연의 부가 호출을 병렬로 시작한다', async () => {
    let release: (() => void) | undefined;
    vi.mocked(getAvailability).mockImplementation(
      () =>
        new Promise((resolve) => {
          release = () => resolve([]);
        }),
    );
    const work = getPerformanceDetail(1);
    await vi.waitFor(() => expect(getBookings).toHaveBeenCalled());
    release?.();
    await work;
  });
  it('마이페이지 핵심 실패를 전파하고 빈 목록에서는 결제 조회를 생략한다', async () => {
    vi.mocked(getBookings).mockRejectedValueOnce(new Error('failed'));
    await expect(getMyPage()).rejects.toThrow('failed');
    expect((await getMyPage()).bookings).toEqual([]);
    expect(getPayments).not.toHaveBeenCalled();
  });
  it('쿠폰 조회가 실패해도 마이페이지는 쿠폰 섹션만 비운다', async () => {
    vi.mocked(listMyCoupons).mockRejectedValue(new ApiError(503));
    const result = await getMyPage();
    expect(result.couponsStatus).toBe('unavailable');
    expect(result.coupons).toEqual([]);
    vi.mocked(listMyCoupons).mockRejectedValue(new SessionExpiredError());
    await expect(getMyPage()).rejects.toBeInstanceOf(SessionExpiredError);
  });
  it('결제 오류는 부분 실패로 표시한다', async () => {
    vi.mocked(getBookings).mockResolvedValue({
      items: [
        {
          bookingId: 1,
          status: 'PENDING',
          performanceId: 2,
          performanceTitle: '공연',
          startsAt: '시간',
          seatCount: 1,
          totalAmount: 20,
          createdAt: '시간',
        },
      ],
      nextCursor: null,
    });
    vi.mocked(getPayments).mockRejectedValue(new ApiError(504));
    const result = await getMyPage();
    expect(result.paymentsStatus).toBe('unavailable');
    expect(result.bookings[0].payment).toBeNull();
  });
  it('대시보드 호출은 병렬이고 둘 다 실패하면 오류다', async () => {
    let release: (() => void) | undefined;
    vi.mocked(getSales).mockImplementation(
      () =>
        new Promise((resolve) => {
          release = () => resolve([]);
        }),
    );
    vi.mocked(getSettlements).mockRejectedValue(new Error('timeout'));
    const work = getOrganizerDashboard({
      from: '2026-01-01',
      to: '2026-01-02',
    });
    await vi.waitFor(() => expect(getSettlements).toHaveBeenCalled());
    release?.();
    expect((await work).settlements.status).toBe('unavailable');
    vi.mocked(getSales).mockRejectedValue(new Error('down'));
    await expect(
      getOrganizerDashboard({ from: 'a', to: 'b' }),
    ).rejects.toThrow();
  });
});
