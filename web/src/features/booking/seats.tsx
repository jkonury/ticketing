'use client';

import { useCallback, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { InlineError } from '@/components/ui/feedback';
import { useEventStream } from '@/features/stream/use-event-stream';
import {
  bookingSchema,
  call,
  changeSchema,
  codeOf,
  errorSchema,
  snapshotSchema,
  type Admission,
  type Booking,
  type Seat,
  type SeatStatus,
} from './booking-shared';
import { bookingError } from './error-messages';
import { SeatGrid, won } from './seat-grid';

export function Seats({
  scheduleId,
  seats,
  admission,
  onBooking,
  onReset,
}: {
  scheduleId: number;
  seats: Seat[];
  admission: Admission;
  onBooking: (booking: Booking) => void;
  onReset: () => void;
}) {
  const [hasSnapshot, setHasSnapshot] = useState(false);
  const [statuses, setStatuses] = useState<Record<number, SeatStatus>>({});
  const [selected, setSelected] = useState<number[]>([]);
  const [notice, setNotice] = useState('');
  const [pending, setPending] = useState(false);
  const [flashing, setFlashing] = useState<ReadonlySet<number>>(new Set());
  const key = useRef(crypto.randomUUID());
  const router = useRouter();
  const apply = useCallback(
    (changes: { seatId: number; status: SeatStatus }[]) => {
      setStatuses((current) => ({
        ...current,
        ...Object.fromEntries(
          changes.map((item) => [item.seatId, item.status]),
        ),
      }));
      const unavailable = new Set(
        changes
          .filter((item) => item.status !== 'AVAILABLE')
          .map((item) => item.seatId),
      );
      if (selected.some((id) => unavailable.has(id))) {
        setNotice('다른 사용자가 선택한 좌석을 제외했습니다.');
        key.current = crypto.randomUUID();
      }
      setSelected((current) => current.filter((id) => !unavailable.has(id)));
    },
    [selected],
  );
  const timers = useRef(new Set<ReturnType<typeof setTimeout>>());
  const clearFlashTimersRef = useCallback((element: HTMLDivElement | null) => {
    if (!element) return;
    const active = timers.current;
    return () => {
      active.forEach(clearTimeout);
      active.clear();
    };
  }, []);
  // 다른 사용자가 선점한 좌석을 잠깐 강조한다. 움직임은 CSS의 motion-safe가 맡는다.
  const flash = useCallback((ids: number[]) => {
    setFlashing((current) => new Set([...current, ...ids]));
    const timer = setTimeout(() => {
      timers.current.delete(timer);
      setFlashing((current) => {
        const next = new Set(current);
        ids.forEach((id) => next.delete(id));
        return next;
      });
    }, 1500);
    timers.current.add(timer);
  }, []);
  const { state, restart, attach } = useEventStream({
    request: { kind: 'seats', scheduleId },
    freshSnapshot: true,
    onDisconnect: () => setHasSnapshot(false),
    handlers: {
      snapshot(data) {
        const parsed = snapshotSchema.safeParse(data);
        if (parsed.success) {
          apply(parsed.data.seats);
          setHasSnapshot(true);
        }
      },
      seat(data) {
        const parsed = changeSchema.safeParse(data);
        if (parsed.success && parsed.data.status === 'HELD')
          flash(parsed.data.seatIds);
        if (parsed.success)
          apply(
            parsed.data.seatIds.map((seatId) => ({
              seatId,
              status: parsed.data.status,
            })),
          );
      },
    },
  });
  const ready = state === 'open' && hasSnapshot;
  async function submit() {
    setPending(true);
    setNotice('');
    try {
      onBooking({
        ...bookingSchema.parse(
          await call('/api/bookings', 'POST', {
            scheduleId,
            seatIds: selected,
            admissionToken: admission.admissionToken,
            idempotencyKey: key.current,
          }),
        ),
        receivedAt: performance.now(),
      });
    } catch (error) {
      const code = codeOf(error);
      const parsed = errorSchema.safeParse(error);
      if (code === 'SESSION_EXPIRED') router.push('/login');
      if (
        code === 'ADMISSION_TOKEN_INVALID' ||
        code === 'ADMISSION_TOKEN_REQUIRED'
      ) {
        onReset();
        return;
      }
      if (
        code === 'SEAT_ALREADY_HELD' &&
        parsed.success &&
        parsed.data.seatIds
      ) {
        const ids = parsed.data.seatIds;
        setSelected((current) => current.filter((id) => !ids.includes(id)));
        key.current = crypto.randomUUID();
      }
      setNotice(bookingError(code));
    } finally {
      setPending(false);
    }
  }
  const chosen = seats.filter((seat) => selected.includes(seat.seatId));
  const total = chosen.reduce((sum, seat) => sum + seat.price, 0);
  const byGrade = [...Map.groupBy(chosen, (seat) => seat.grade)];
  function toggle(seat: Seat) {
    const isSelected = selected.includes(seat.seatId);
    if (!isSelected && selected.length >= 4) {
      setNotice(bookingError('TOO_MANY_SEATS'));
      return;
    }
    setSelected(
      isSelected
        ? selected.filter((id) => id !== seat.seatId)
        : [...selected, seat.seatId],
    );
    key.current = crypto.randomUUID();
  }
  return (
    <div
      ref={clearFlashTimersRef}
      className="grid grid-cols-[minmax(0,1fr)] gap-4 pb-24 lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start lg:pb-0"
    >
      <Card
        ref={(element) => attach(element)}
        padding="none"
        className="grid grid-cols-[minmax(0,1fr)] gap-4 overflow-visible! p-4 lg:p-6"
      >
        <h2 className="text-heading font-bold text-fg">좌석 선택</h2>
        <p className="text-caption text-fg-muted">
          최대 4석까지 고를 수 있어요. 좌석 현황은 참고용이고, 실제 선점 결과는
          예매할 때 확정돼요.
        </p>
        {state === 'failed' ? (
          <InlineError role="alert">
            좌석 정보를 연결할 수 없습니다.{' '}
            <Button variant="secondary" onClick={restart}>
              다시 연결
            </Button>
          </InlineError>
        ) : !ready ? (
          <p role="status" className="text-caption text-fg-muted">
            좌석 정보를{' '}
            {state === 'reconnecting'
              ? '다시 연결하고 있습니다. 표시된 정보는 오래되었을 수 있습니다.'
              : '확인하고 있습니다.'}
          </p>
        ) : null}
        <SeatGrid
          seats={seats}
          statuses={statuses}
          selected={selected}
          flashing={flashing}
          interactive={ready && !pending}
          onToggle={toggle}
        />
      </Card>
      <aside
        aria-label="선택 요약"
        className="fixed inset-x-0 bottom-0 z-30 grid gap-3 border-t border-line bg-surface p-3 shadow-bar lg:sticky lg:top-20 lg:rounded-card lg:border lg:p-5 lg:shadow-none"
      >
        <div className="hidden gap-2 lg:grid">
          <h3 className="text-body font-semibold text-fg">선택한 좌석</h3>
          {chosen.length ? (
            <ul className="grid gap-1 text-caption text-fg">
              {chosen.map((seat) => (
                <li key={seat.seatId} className="flex justify-between gap-2">
                  <span>
                    {seat.section}구역 {seat.row}열 {seat.number}번
                  </span>
                  <span className="text-fg-muted">
                    {seat.grade} {won(seat.price)}
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-caption text-fg-muted">선택한 좌석이 없어요.</p>
          )}
          {byGrade.length > 0 && (
            <ul className="grid gap-1 border-t border-line pt-2 text-caption text-fg-muted">
              {byGrade.map(([grade, items]) => (
                <li key={grade} className="flex justify-between gap-2">
                  <span>
                    {grade}석 {items.length}석
                  </span>
                  <span>
                    {won(items.reduce((sum, seat) => sum + seat.price, 0))}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
        {notice && <InlineError role="alert">{notice}</InlineError>}
        <div className="flex items-center justify-between gap-3 lg:grid">
          <p className="flex gap-2 text-body font-bold text-fg lg:justify-between">
            <span>
              {selected.length ? `${selected.length}석` : '선택 없음'}
            </span>
            <span>
              <span className="sr-only">합계 </span>
              {won(total)}
            </span>
          </p>
          <Button
            className="lg:w-full"
            disabled={!ready || !selected.length || pending}
            onClick={() => void submit()}
          >
            {pending ? '예매 중' : '예매하기'}
          </Button>
        </div>
      </aside>
    </div>
  );
}
