'use client';

import { useCallback, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
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
  const stream = useEventStream({
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
  const ready = stream.state === 'open' && hasSnapshot;
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
  return (
    <section>
      <h2>좌석 선택</h2>
      <p>최대 4석</p>
      {stream.state === 'failed' ? (
        <p role="alert">
          좌석 정보를 연결할 수 없습니다.{' '}
          <button onClick={stream.restart}>다시 연결</button>
        </p>
      ) : !ready ? (
        <p role="status">
          좌석 정보를{' '}
          {stream.state === 'reconnecting'
            ? '다시 연결하고 있습니다. 표시된 정보는 오래되었을 수 있습니다.'
            : '확인하고 있습니다.'}
        </p>
      ) : null}
      <p className="field-hint">
        좌석은 선택 가능, 선점됨, 판매됨 상태로 표시됩니다. 좌석 배치는 가로로
        스크롤할 수 있습니다.
      </p>
      <div
        className="seat-map"
        tabIndex={0}
        role="region"
        aria-label="좌석 배치"
      >
        {Array.from(new Set(seats.map((seat) => seat.section))).map(
          (section) => (
            <fieldset key={section}>
              <legend>{section}구역</legend>
              {Array.from(
                new Set(
                  seats
                    .filter((seat) => seat.section === section)
                    .map((seat) => seat.row),
                ),
              ).map((row) => (
                <div key={row}>
                  <span>{row}열 </span>
                  {seats
                    .filter(
                      (seat) => seat.section === section && seat.row === row,
                    )
                    .map((seat) => {
                      const status = statuses[seat.seatId];
                      const selectedSeat = selected.includes(seat.seatId);
                      const label =
                        status === 'AVAILABLE'
                          ? '선택 가능'
                          : status === 'HELD'
                            ? '선점됨'
                            : status === 'SOLD'
                              ? '판매됨'
                              : '확인 중';
                      return (
                        <button
                          key={seat.seatId}
                          type="button"
                          aria-label={`${seat.section}구역 ${seat.row}열 ${seat.number}번, ${seat.grade}석, ${label}`}
                          aria-pressed={selectedSeat}
                          className="seat-button"
                          disabled={!ready || status !== 'AVAILABLE' || pending}
                          onClick={() => {
                            if (!selectedSeat && selected.length >= 4) {
                              setNotice(bookingError('TOO_MANY_SEATS'));
                              return;
                            }
                            setSelected(
                              selectedSeat
                                ? selected.filter((id) => id !== seat.seatId)
                                : [...selected, seat.seatId],
                            );
                            key.current = crypto.randomUUID();
                          }}
                        >
                          {seat.section} {seat.row}-{seat.number} ({seat.grade})
                        </button>
                      );
                    })}
                </div>
              ))}
            </fieldset>
          ),
        )}
      </div>
      <p>
        선택한 좌석:{' '}
        {selected.length
          ? seats
              .filter((seat) => selected.includes(seat.seatId))
              .map((seat) => `${seat.section} ${seat.row}-${seat.number}`)
              .join(', ')
          : '없음'}
      </p>
      {notice && <p role="alert">{notice}</p>}
      <button
        disabled={!ready || !selected.length || pending}
        onClick={() => void submit()}
      >
        {pending ? '예매 중' : '예매하기'}
      </button>
    </section>
  );
}
