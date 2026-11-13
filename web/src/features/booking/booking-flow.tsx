'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { formatSeoul } from '@/lib/datetime';
import {
  call,
  type Admission,
  type Booking,
  type Schedule,
  type Seat,
} from './booking-shared';
import { Queue } from './queue';
import { Seats } from './seats';
import { Payment } from './payment';

export type { Seat } from './booking-shared';

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
