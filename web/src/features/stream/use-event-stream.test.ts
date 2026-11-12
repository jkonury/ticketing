import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useEventStream } from './use-event-stream';

vi.mock('next/navigation', () => {
  const router = { push: vi.fn() };
  return { useRouter: () => router };
});
class FakeSource extends EventTarget {
  static instances: FakeSource[] = [];
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  close = vi.fn();
  constructor(public url: string) {
    super();
    FakeSource.instances.push(this);
  }
  emit(type: string, data: unknown, id = '') {
    this.dispatchEvent(
      new MessageEvent(type, { data: JSON.stringify(data), lastEventId: id }),
    );
  }
}
describe('useEventStream', () => {
  beforeEach(() => {
    FakeSource.instances = [];
    vi.stubGlobal('EventSource', FakeSource);
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ url: 'http://gateway/ticket' }),
      }),
    );
    vi.spyOn(Math, 'random').mockReturnValue(0.5);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
  it('오류에서 닫고 새 티켓으로 마지막 좌석 이벤트부터 재연결하며 언마운트 때 닫는다', async () => {
    const { unmount } = renderHook(() =>
      useEventStream({
        request: { kind: 'seats', scheduleId: 1 },
        handlers: { seat: vi.fn() },
      }),
    );
    await waitFor(() => expect(FakeSource.instances).toHaveLength(1));
    act(() => FakeSource.instances[0].emit('seat', { seatIds: [1] }, '12-0'));
    vi.useFakeTimers();
    act(() => FakeSource.instances[0].onerror?.());
    expect(FakeSource.instances[0].close).toHaveBeenCalled();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(
      JSON.parse(vi.mocked(fetch).mock.calls[1][1]?.body as string),
    ).toMatchObject({ lastEventId: '12-0' });
    expect(FakeSource.instances).toHaveLength(2);
    unmount();
    expect(FakeSource.instances[1].close).toHaveBeenCalled();
  });
  it('핸들러가 정상 종료하면 재연결하지 않는다', async () => {
    const { result } = renderHook(() =>
      useEventStream({
        request: { kind: 'queue', scheduleId: 1 },
        handlers: { admitted: () => result.current.close() },
      }),
    );
    await waitFor(() => expect(FakeSource.instances).toHaveLength(1));
    act(() => FakeSource.instances[0].emit('admitted', {}));
    expect(FakeSource.instances[0].close).toHaveBeenCalled();
    vi.useFakeTimers();
    act(() => FakeSource.instances[0].onerror?.());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(31000);
    });
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
