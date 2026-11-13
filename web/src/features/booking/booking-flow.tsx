'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { z } from 'zod';
import { formatSeoul } from '@/lib/datetime';
import { useEventStream } from '@/features/stream/use-event-stream';
import { bookingError } from './error-messages';

export type Seat = {
  seatId: number;
  section: string;
  row: string;
  number: number;
  grade: string;
  price: number;
};
type Schedule = { id: number; performanceTitle: string; startsAt: string };
type Admission = { admissionToken: string; expiresAt: string };
type Booking = {
  bookingId: number;
  expiresAt: string;
  totalAmount: number;
  seats: { seatId: number; label: string; price: number }[];
};
const admissionSchema = z.object({
  admissionToken: z.string(),
  expiresAt: z.string(),
});
const queueSchema = z.object({
  status: z.enum(['WAITING', 'ADMITTED']),
  position: z.number().nullish(),
  admissionToken: z.string().nullish(),
  expiresAt: z.string().nullish(),
});
const bookingSchema = z.object({
  bookingId: z.number(),
  expiresAt: z.string(),
  totalAmount: z.number(),
  seats: z.array(
    z.object({ seatId: z.number(), label: z.string(), price: z.number() }),
  ),
});
const errorSchema = z.object({
  code: z.string(),
  seatIds: z.array(z.number()).optional(),
});
const statusSchema = z.enum(['AVAILABLE', 'HELD', 'SOLD']);
type SeatStatus = z.infer<typeof statusSchema>;
const snapshotSchema = z.object({
  seats: z.array(z.object({ seatId: z.number(), status: statusSchema })),
});
const changeSchema = z.object({
  seatIds: z.array(z.number()),
  status: statusSchema,
});

async function call(path: string, method: string, body?: unknown) {
  const response = await fetch(path, {
    method,
    ...(body === undefined
      ? {}
      : {
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        }),
  });
  const data: unknown = response.status === 204 ? null : await response.json();
  if (!response.ok) throw errorSchema.parse(data);
  return data;
}
function codeOf(error: unknown) {
  const result = errorSchema.safeParse(error);
  return result.success ? result.data.code : 'UNKNOWN';
}

export function BookingFlow({
  schedule,
  seats,
}: {
  schedule: Schedule;
  seats: Seat[];
}) {
  const [admission, setAdmission] = useState<Admission | null>(null);
  const [booking, setBooking] = useState<Booking | null>(null);
  const [done, setDone] = useState(false);
  const [queueVersion, setQueueVersion] = useState(0);
  useEffect(
    () => () => {
      void call(`/api/queues/${schedule.id}/entries/me`, 'DELETE').catch(
        () => {},
      );
    },
    [schedule.id],
  );
  const reset = () => {
    setAdmission(null);
    setBooking(null);
    setQueueVersion((value) => value + 1);
  };
  return (
    <main className="booking-page">
      <h1>{schedule.performanceTitle}</h1>
      <p>{formatSeoul(schedule.startsAt)}</p>
      {done && booking ? (
        <section>
          <h2>예매 완료</h2>
          <p>예매 번호: {booking.bookingId}</p>
          <p>{booking.seats.map((seat) => seat.label).join(', ')}</p>
          <p>{booking.totalAmount.toLocaleString('ko-KR')}원</p>
          <Link href="/my">마이페이지</Link>
        </section>
      ) : booking ? (
        <Payment
          booking={booking}
          onDone={() => setDone(true)}
          onReset={reset}
        />
      ) : admission ? (
        <Seats
          scheduleId={schedule.id}
          seats={seats}
          admission={admission}
          onBooking={setBooking}
          onReset={reset}
        />
      ) : (
        <Queue
          key={queueVersion}
          scheduleId={schedule.id}
          onAdmitted={setAdmission}
        />
      )}
    </main>
  );
}

function Queue({
  scheduleId,
  onAdmitted,
}: {
  scheduleId: number;
  onAdmitted: (admission: Admission) => void;
}) {
  const [waiting, setWaiting] = useState(false);
  const [removed, setRemoved] = useState(false);
  const [position, setPosition] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const router = useRouter();
  useEffect(() => {
    let active = true;
    void call(`/api/queues/${scheduleId}/entries`, 'POST')
      .then((data) => {
        if (!active) return;
        const entry = queueSchema.parse(data);
        if (entry.status === 'ADMITTED')
          onAdmitted(admissionSchema.parse(entry));
        else {
          setPosition(entry.position ?? null);
          setWaiting(true);
        }
      })
      .catch((failure: unknown) => {
        if (active) {
          const code = codeOf(failure);
          if (code === 'SESSION_EXPIRED') router.push('/login');
          setError(bookingError(code));
        }
      });
    return () => {
      active = false;
    };
  }, [scheduleId, attempt, onAdmitted, router]);
  const stream = useEventStream({
    request: { kind: 'queue', scheduleId },
    enabled: waiting,
    handlers: {
      position(data) {
        const parsed = z.object({ position: z.number() }).safeParse(data);
        if (parsed.success) setPosition(parsed.data.position);
      },
      admitted(data) {
        const parsed = admissionSchema.safeParse(data);
        if (parsed.success) {
          stream.close();
          onAdmitted(parsed.data);
        }
      },
      removed() {
        stream.close();
        setWaiting(false);
        setRemoved(true);
      },
    },
  });
  return (
    <section>
      <h2>대기열</h2>
      {waiting && <p>대기 순번: {position ?? '확인 중'}</p>}
      {removed && (
        <>
          <p>대기열에서 제외되었습니다.</p>
          <button
            onClick={() => {
              setRemoved(false);
              setAttempt((value) => value + 1);
            }}
          >
            다시 줄 서기
          </button>
        </>
      )}
      {error && <p role="alert">{error}</p>}
    </section>
  );
}

function Seats({
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
  useEventStream({
    request: { kind: 'seats', scheduleId },
    handlers: {
      snapshot(data) {
        const parsed = snapshotSchema.safeParse(data);
        if (parsed.success) apply(parsed.data.seats);
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
  async function submit() {
    setPending(true);
    setNotice('');
    try {
      onBooking(
        bookingSchema.parse(
          await call('/api/bookings', 'POST', {
            scheduleId,
            seatIds: selected,
            admissionToken: admission.admissionToken,
            idempotencyKey: key.current,
          }),
        ),
      );
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
                          disabled={status !== 'AVAILABLE' || pending}
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
        disabled={!selected.length || pending}
        onClick={() => void submit()}
      >
        {pending ? '예매 중' : '예매하기'}
      </button>
    </section>
  );
}

function Payment({
  booking,
  onDone,
  onReset,
}: {
  booking: Booking;
  onDone: () => void;
  onReset: () => void;
}) {
  const [remaining, setRemaining] = useState(() =>
    Math.max(0, Math.ceil((Date.parse(booking.expiresAt) - Date.now()) / 1000)),
  );
  const [pending, setPending] = useState(false);
  const [notice, setNotice] = useState('');
  const paymentKey = useRef(crypto.randomUUID());
  const router = useRouter();
  useEffect(() => {
    const timer = setInterval(
      () =>
        setRemaining(
          Math.max(
            0,
            Math.ceil((Date.parse(booking.expiresAt) - Date.now()) / 1000),
          ),
        ),
      1000,
    );
    return () => clearInterval(timer);
  }, [booking.expiresAt]);
  const handleFailure = (error: unknown) => {
    const code = codeOf(error);
    if (code === 'SESSION_EXPIRED') router.push('/login');
    if (
      code === 'ADMISSION_TOKEN_INVALID' ||
      code === 'ADMISSION_TOKEN_REQUIRED'
    ) {
      onReset();
      return;
    }
    setNotice(bookingError(code));
  };
  async function pay() {
    setPending(true);
    setNotice('');
    try {
      await call(`/api/bookings/${booking.bookingId}/payments`, 'POST', {
        idempotencyKey: paymentKey.current,
      });
      for (let attempt = 0; attempt < 30; attempt++) {
        await new Promise((resolve) => setTimeout(resolve, 1000));
        const result = z
          .object({ status: z.string() })
          .parse(await call(`/api/bookings/${booking.bookingId}`, 'GET'));
        if (result.status === 'CONFIRMED') {
          onDone();
          return;
        }
        if (result.status === 'EXPIRED' || result.status === 'CANCELLED') {
          setNotice('결제가 실패했거나 예매가 만료되었습니다.');
          return;
        }
      }
      setNotice('처리 중입니다. 마이페이지에서 확인하세요.');
    } catch (error) {
      handleFailure(error);
    } finally {
      setPending(false);
    }
  }
  async function cancel() {
    setPending(true);
    try {
      await call(`/api/bookings/${booking.bookingId}/cancel`, 'POST');
      onReset();
    } catch (error) {
      handleFailure(error);
    } finally {
      setPending(false);
    }
  }
  return (
    <section>
      <h2>결제</h2>
      <p>선택한 좌석: {booking.seats.map((seat) => seat.label).join(', ')}</p>
      <p>남은 시간: {remaining}초</p>
      <p>{booking.totalAmount.toLocaleString('ko-KR')}원</p>
      {remaining === 0 && <p>선점이 만료되었습니다.</p>}
      {notice && (
        <p role="alert">
          {notice} <Link href="/my">마이페이지</Link>
        </p>
      )}
      <button disabled={pending || remaining === 0} onClick={() => void pay()}>
        결제하기
      </button>
      <button disabled={pending} onClick={() => void cancel()}>
        취소
      </button>
    </section>
  );
}
