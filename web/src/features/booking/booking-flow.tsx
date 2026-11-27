'use client';

import { useCallback, useRef, useState } from 'react';
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
  const pendingLeave = useRef<{
    scheduleId: number;
    timer: ReturnType<typeof setTimeout>;
  } | null>(null);
  const scheduleId = schedule.id;
  const bookingPageRef = useCallback(
    (element: HTMLElement | null) => {
      if (!element) return;
      // 개발 모드에서 같은 회차의 효과가 즉시 다시 실행되면 이탈을 취소한다.
      if (pendingLeave.current?.scheduleId === scheduleId) {
        clearTimeout(pendingLeave.current.timer);
        pendingLeave.current = null;
      }
      return () => {
        const timer = setTimeout(() => {
          void call(`/api/queues/${scheduleId}/entries/me`, 'DELETE').catch(
            () => {},
          );
        }, 0);
        pendingLeave.current = { scheduleId, timer };
      };
    },
    [scheduleId],
  );
  const reset = () => {
    setAdmission(null);
    setBooking(null);
    setQueueVersion((value) => value + 1);
  };
  return (
    <Page ref={bookingPageRef}>
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
