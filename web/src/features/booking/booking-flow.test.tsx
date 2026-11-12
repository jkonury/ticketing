import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BookingFlow } from './booking-flow';

const stream = vi.hoisted(() => ({
  handlers: {} as Record<string, (data: unknown, id: string | null) => void>,
}));
vi.mock('next/navigation', () => {
  const router = { push: vi.fn() };
  return { useRouter: () => router };
});
vi.mock('@/features/stream/use-event-stream', () => ({
  useEventStream: ({
    request,
    handlers,
  }: {
    request: { kind: string };
    handlers: typeof stream.handlers;
  }) => {
    if (request.kind === 'seats') stream.handlers = handlers;
    return { state: 'open', close: vi.fn() };
  },
}));
const seats = Array.from({ length: 5 }, (_, index) => ({
  seatId: index + 1,
  section: 'A',
  row: '3',
  number: index + 1,
  grade: 'R',
  price: 10000,
}));
const schedule = {
  id: 1,
  performanceTitle: '공연',
  startsAt: '2027-01-01T10:00:00Z',
};
function response(data: unknown, status = 200) {
  return { ok: status < 400, status, json: async () => data };
}
describe('예매 흐름', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((path: string) =>
        Promise.resolve(
          path.includes('/queues/')
            ? response({
                status: 'ADMITTED',
                admissionToken: 'opaque',
                expiresAt: '2099-01-01T00:00:00Z',
              })
            : response(null, 204),
        ),
      ),
    );
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
  async function openSeats() {
    render(<BookingFlow schedule={schedule} seats={seats} />);
    await screen.findByText('좌석 선택');
    act(() =>
      stream.handlers.snapshot(
        {
          seats: seats.map((seat) => ({
            seatId: seat.seatId,
            status: 'AVAILABLE',
          })),
        },
        null,
      ),
    );
  }
  it('좌석은 4개까지만 선택하고 선점된 좌석을 선택에서 뺀다', async () => {
    await openSeats();
    for (let index = 1; index <= 5; index++)
      fireEvent.click(
        screen.getByRole('button', {
          name: `A구역 3열 ${index}번, R석, 선택 가능`,
        }),
      );
    expect(
      screen.getByRole('button', { name: 'A구역 3열 5번, R석, 선택 가능' }),
    ).toHaveAttribute('aria-pressed', 'false');
    act(() => stream.handlers.seat({ seatIds: [1], status: 'HELD' }, '1-0'));
    expect(
      screen.getByRole('button', { name: 'A구역 3열 1번, R석, 선점됨' }),
    ).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('alert')).toHaveTextContent('다른 사용자');
  });
  it('결제 중 버튼을 비활성화하고 확정 결과를 표시한다', async () => {
    vi.mocked(fetch).mockImplementation((path) => {
      const url = String(path);
      if (url.includes('/queues/'))
        return Promise.resolve(
          response({
            status: 'ADMITTED',
            admissionToken: 'opaque',
            expiresAt: '2099-01-01T00:00:00Z',
          }) as Response,
        );
      if (url === '/api/bookings')
        return Promise.resolve(
          response({
            bookingId: 4,
            expiresAt: '2099-01-01T00:00:00Z',
            totalAmount: 10000,
            seats: [{ seatId: 1, label: 'A 3-1', price: 10000 }],
          }) as Response,
        );
      if (url.endsWith('/payments'))
        return Promise.resolve(
          response({ status: 'PENDING' }, 202) as Response,
        );
      return Promise.resolve(response({ status: 'CONFIRMED' }) as Response);
    });
    await openSeats();
    fireEvent.click(
      screen.getByRole('button', { name: 'A구역 3열 1번, R석, 선택 가능' }),
    );
    fireEvent.click(screen.getByRole('button', { name: '예매하기' }));
    const pay = await screen.findByRole('button', { name: '결제하기' });
    fireEvent.click(pay);
    expect(pay).toBeDisabled();
    expect(
      await screen.findByText('예매 완료', {}, { timeout: 3000 }),
    ).toBeInTheDocument();
  });
  it('입장 토큰 오류 시 대기열 단계로 복귀한다', async () => {
    vi.mocked(fetch).mockImplementation((path) =>
      Promise.resolve(
        (String(path) === '/api/bookings'
          ? response({ code: 'ADMISSION_TOKEN_INVALID' }, 403)
          : response({ status: 'WAITING', position: 2 })) as Response,
      ),
    );
    // 첫 등록만 입장 상태로 만든다.
    vi.mocked(fetch).mockResolvedValueOnce(
      response({
        status: 'ADMITTED',
        admissionToken: 'opaque',
        expiresAt: '2099-01-01T00:00:00Z',
      }) as Response,
    );
    await openSeats();
    fireEvent.click(
      screen.getByRole('button', { name: 'A구역 3열 1번, R석, 선택 가능' }),
    );
    fireEvent.click(screen.getByRole('button', { name: '예매하기' }));
    await waitFor(() => expect(screen.getByText('대기열')).toBeInTheDocument());
  });
});
