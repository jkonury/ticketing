'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { Page, PageHeader } from '@/components/layout/page';
import { buttonClassName } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
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
    <Page>
      <PageHeader
        title={schedule.performanceTitle}
        description={formatSeoul(schedule.startsAt)}
      />
      {done && booking ? (
        <Card padding="lg" className="grid gap-4">
          <h2 className="text-heading font-bold text-fg">예매 완료</h2>
          <p>예매 번호: {booking.bookingId}</p>
          <p>{booking.seats.map((seat) => seat.label).join(', ')}</p>
          <p>{booking.totalAmount.toLocaleString('ko-KR')}원</p>
          <div>
            <Link href="/my" className={buttonClassName()}>
              마이페이지
            </Link>
          </div>
        </Card>
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
    </Page>
  );
}
