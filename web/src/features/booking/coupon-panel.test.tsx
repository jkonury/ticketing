import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Payment } from './payment';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));

function response(data: unknown, status = 200) {
  return { ok: status < 400, status, json: async () => data };
}
const booking = {
  bookingId: 1,
  expiresAt: '2099-01-01T00:00:00Z',
  serverTime: '2098-12-31T23:00:00Z',
  receivedAt: 0,
  totalAmount: 10000,
  seats: [],
};
const coupons = [
  {
    id: 7,
    status: 'ISSUED',
    definition: {
      name: '1000원 쿠폰',
      validFrom: '2000-01-01T00:00:00Z',
      validUntil: '2099-01-01T00:00:00Z',
      performanceId: null,
    },
  },
];
const reserving = {
  payableAmount: 10000,
  coupon: { issuedCouponId: 7, state: 'RESERVING' },
};
function setup(result: unknown) {
  let polled = 0;
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation((path: string, init?: { method?: string }) => {
      if (path === '/api/me/coupons') return Promise.resolve(response(coupons));
      if (init?.method === 'PUT') return Promise.resolve(response(reserving));
      polled += 1;
      return Promise.resolve(response(polled < 2 ? reserving : result));
    }),
  );
  render(<Payment booking={booking} onDone={vi.fn()} onReset={vi.fn()} />);
}
async function apply() {
  fireEvent.change(await screen.findByLabelText('쿠폰 선택'), {
    target: { value: '7' },
  });
  fireEvent.click(screen.getByRole('button', { name: '쿠폰 적용' }));
}
describe('결제 화면 쿠폰', () => {
  beforeEach(() => vi.useRealTimers());
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });
  it('적용 후 RESERVING 동안 결제를 막고 RESERVED가 되면 결제 금액을 보여 준다', async () => {
    setup({
      payableAmount: 9000,
      coupon: { issuedCouponId: 7, state: 'RESERVED', discountAmount: 1000 },
    });
    await apply();
    expect(await screen.findByText('쿠폰 적용 중입니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '결제하기' })).toBeDisabled();
    expect(
      await screen.findByText('쿠폰 적용됨: -1,000원', {}, { timeout: 4000 }),
    ).toBeInTheDocument();
    expect(screen.getByText('결제 금액: 9,000원')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '결제하기' })).toBeEnabled();
  });
  it('REJECTED면 사유를 보여 주고 정가로 결제할 수 있다', async () => {
    setup({
      payableAmount: 10000,
      coupon: {
        issuedCouponId: 7,
        state: 'REJECTED',
        rejectReason: 'BELOW_MIN_ORDER_AMOUNT',
      },
    });
    await apply();
    expect(
      await screen.findByText(
        /최소 주문 금액에 못 미칩니다/,
        {},
        { timeout: 4000 },
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('결제 금액: 10,000원')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '결제하기' })).toBeEnabled();
  });

  it('결과 조회 중 쿠폰을 해제하면 늦은 조회 응답이 와도 정가로 결제할 수 있다', async () => {
    const poll = Promise.withResolvers<ReturnType<typeof response>>();
    const fetch = vi.fn((path: string, init?: { method?: string }) => {
      if (path === '/api/me/coupons') return Promise.resolve(response(coupons));
      if (init?.method === 'PUT') return Promise.resolve(response(reserving));
      if (init?.method === 'DELETE') {
        return Promise.resolve(
          response({ payableAmount: 10000, coupon: null }),
        );
      }
      return poll.promise;
    });
    vi.stubGlobal('fetch', fetch);
    render(<Payment booking={booking} onDone={vi.fn()} onReset={vi.fn()} />);

    await apply();
    await waitFor(
      () =>
        expect(fetch).toHaveBeenCalledWith('/api/bookings/1', {
          method: 'GET',
        }),
      { timeout: 3000 },
    );
    const remove = screen.getByRole('button', { name: '쿠폰 해제' });
    expect(remove).toBeEnabled();
    expect(screen.getByRole('button', { name: '쿠폰 적용' })).toBeDisabled();
    fireEvent.click(remove);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '결제하기' })).toBeEnabled(),
    );

    await act(async () => {
      poll.resolve(
        response({
          payableAmount: 9000,
          coupon: {
            issuedCouponId: 7,
            state: 'RESERVED',
            discountAmount: 1000,
          },
        }),
      );
    });
    expect(screen.queryByRole('button', { name: '쿠폰 해제' })).toBeNull();
    expect(screen.getByText('결제 금액: 10,000원')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '결제하기' })).toBeEnabled();
  });
});
