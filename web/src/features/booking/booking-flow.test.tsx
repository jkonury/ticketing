import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BookingFlow } from './booking-flow';
import { Payment } from './payment';
import { Seats } from './seats';
import { Queue } from './queue';

const stream = vi.hoisted(() => ({
  state: 'open',
  onDisconnect: () => {},
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
    onDisconnect,
  }: {
    request: { kind: string };
    handlers: typeof stream.handlers;
    onDisconnect?: () => void;
  }) => {
    if (request.kind === 'seats') {
      stream.handlers = handlers;
      stream.onDisconnect = onDisconnect ?? (() => {});
    }
    return { state: stream.state, close: vi.fn(), restart: vi.fn() };
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
    stream.state = 'open';
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
  it('바로 입장하면 대기열을 표시하지 않는다', async () => {
    render(<BookingFlow schedule={schedule} seats={seats} />);
    expect(screen.queryByText('대기열')).not.toBeInTheDocument();
    await screen.findByText('좌석 선택');
    expect(screen.queryByText('대기열')).not.toBeInTheDocument();
  });
  it('좌석은 4개까지만 선택하고 선점된 좌석을 선택에서 뺀다', async () => {
    await openSeats();
    for (let index = 1; index <= 5; index++)
      fireEvent.click(
        screen.getByRole('button', {
          name: `A구역 3열 ${index}번, R석 10,000원, 선택 가능`,
        }),
      );
    expect(
      screen.getByRole('button', {
        name: 'A구역 3열 5번, R석 10,000원, 선택 가능',
      }),
    ).toHaveAttribute('aria-pressed', 'false');
    act(() => stream.handlers.seat({ seatIds: [1], status: 'HELD' }, '1-0'));
    expect(
      screen.getByRole('button', {
        name: 'A구역 3열 1번, R석 10,000원, 선점됨',
      }),
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
            serverTime: '2026-01-01T00:00:00Z',
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
      screen.getByRole('button', {
        name: 'A구역 3열 1번, R석 10,000원, 선택 가능',
      }),
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
      screen.getByRole('button', {
        name: 'A구역 3열 1번, R석 10,000원, 선택 가능',
      }),
    );
    fireEvent.click(screen.getByRole('button', { name: '예매하기' }));
    expect(screen.queryByText('대기열')).not.toBeInTheDocument();
    expect(
      await screen.findByText('대기 순번: 2', {}, { timeout: 3000 }),
    ).toBeInTheDocument();
  });
  it.each(['2000-01-01', '2090-01-01'])(
    '기기 시각이 %s여도 서버 기준 만료 경계에서 결제를 막고 안내한다',
    (deviceTime) => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date(deviceTime));
      const onReset = vi.fn();
      render(
        <Payment
          booking={{
            bookingId: 1,
            expiresAt: '2026-01-01T00:00:02Z',
            serverTime: '2026-01-01T00:00:00Z',
            receivedAt: performance.now(),
            totalAmount: 1,
            seats: [],
          }}
          onDone={vi.fn()}
          onReset={onReset}
        />,
      );
      expect(screen.getByText('00:02')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '결제하기' })).toBeEnabled();
      act(() => vi.advanceTimersByTime(2000));
      expect(
        screen.getByRole('dialog', { name: '선점 시간이 끝났어요' }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole('button', { name: '결제하기', hidden: true }),
      ).toBeDisabled();
      expect(onReset).not.toHaveBeenCalled();
      fireEvent.click(
        screen.getByRole('button', { name: '좌석 다시 선택하기' }),
      );
      expect(onReset).toHaveBeenCalledTimes(1);
      vi.useRealTimers();
    },
  );
  it('남은 시간을 mm:ss로 보이고 60초 이하에서 경고를 한 번만 알린다', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'));
    render(
      <Payment
        booking={{
          bookingId: 1,
          expiresAt: '2026-01-01T00:01:03Z',
          serverTime: '2026-01-01T00:00:00Z',
          receivedAt: performance.now(),
          totalAmount: 1,
          seats: [],
        }}
        onDone={vi.fn()}
        onReset={vi.fn()}
      />,
    );
    expect(screen.getByText('01:03')).toBeInTheDocument();
    const bar = screen.getByRole('progressbar');
    expect(bar).toHaveAttribute('aria-valuemax', '63');
    expect(bar).toHaveAttribute('aria-valuenow', '63');
    expect(screen.queryByText(/1분 남았어요/)).toBeNull();
    act(() => vi.advanceTimersByTime(3000));
    expect(screen.getByText('01:00')).toBeInTheDocument();
    expect(screen.getAllByText(/1분 남았어요/)).toHaveLength(1);
    act(() => vi.advanceTimersByTime(10000));
    expect(screen.getByText('00:50')).toBeInTheDocument();
    expect(screen.getAllByText(/1분 남았어요/)).toHaveLength(1);
    expect(screen.getByRole('progressbar')).toHaveAttribute(
      'aria-valuenow',
      '50',
    );
    vi.useRealTimers();
  });
  it('좌석 최초 연결과 단절 후 새 스냅샷을 기다리며 제출을 막는다', () => {
    stream.state = 'connecting';
    const props = {
      scheduleId: 1,
      seats,
      admission: {
        admissionToken: 'opaque',
        expiresAt: '2099-01-01T00:00:00Z',
      },
      onBooking: vi.fn(),
      onReset: vi.fn(),
    };
    const view = render(<Seats {...props} />);
    expect(
      screen.getByText('좌석 정보를 확인하고 있습니다.'),
    ).toBeInTheDocument();
    stream.state = 'open';
    view.rerender(<Seats {...props} />);
    act(() =>
      stream.handlers.snapshot(
        { seats: [{ seatId: 1, status: 'AVAILABLE' }] },
        null,
      ),
    );
    fireEvent.click(
      screen.getByRole('button', {
        name: 'A구역 3열 1번, R석 10,000원, 선택 가능',
      }),
    );
    expect(screen.getByRole('button', { name: '예매하기' })).toBeEnabled();
    act(() => stream.onDisconnect());
    stream.state = 'reconnecting';
    view.rerender(<Seats {...props} />);
    expect(
      screen.getByText(/표시된 정보는 오래되었을 수 있습니다/),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '예매하기' })).toBeDisabled();
    stream.state = 'open';
    view.rerender(<Seats {...props} />);
    expect(screen.getByRole('button', { name: '예매하기' })).toBeDisabled();
    act(() =>
      stream.handlers.snapshot(
        { seats: [{ seatId: 1, status: 'AVAILABLE' }] },
        null,
      ),
    );
    expect(screen.getByRole('button', { name: '예매하기' })).toBeEnabled();
  });
  it('대기열 연결 실패와 재연결 상태를 알린다', async () => {
    vi.mocked(fetch).mockResolvedValue(
      response({ status: 'WAITING', position: 2 }) as Response,
    );
    stream.state = 'connecting';
    const props = { scheduleId: 1, onAdmitted: vi.fn() };
    const view = render(<Queue {...props} />);
    expect(
      await screen.findByText('대기열에 연결하고 있습니다.'),
    ).toBeInTheDocument();
    stream.state = 'reconnecting';
    view.rerender(<Queue {...props} />);
    expect(
      screen.getByText(/순번이 오래되었을 수 있습니다/),
    ).toBeInTheDocument();
    stream.state = 'failed';
    view.rerender(<Queue {...props} />);
    expect(screen.getByText(/대기열 연결에 실패했습니다/)).toBeInTheDocument();
    stream.state = 'open';
    view.rerender(<Queue {...props} />);
    expect(
      screen.queryByText(/대기열 연결에 실패했습니다/),
    ).not.toBeInTheDocument();
  });
});
