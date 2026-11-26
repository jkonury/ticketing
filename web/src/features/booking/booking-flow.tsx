'use client';

import { useEffect, useState } from 'react';
import { Page, PageHeader } from '@/components/layout/page';
import { formatSeoul } from '@/lib/datetime';
import {
  call,
  type Admission,
  type Booking,
  type Schedule,
  type Seat,
} from './booking-shared';
import { BookingDone } from './booking-done';
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
        <BookingDone schedule={schedule} booking={booking} />
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
          title={schedule.performanceTitle}
          startsAt={schedule.startsAt}
          onAdmitted={setAdmission}
        />
      )}
    </Page>
  );
}
