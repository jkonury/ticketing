import Link from 'next/link';
import { buttonClassName } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Poster } from '@/components/ui/poster';
import { formatSeoul } from '@/lib/datetime';
import type { Booking, Schedule } from './booking-shared';

/** 예매 완료 티켓 카드. 입장 기능이 없으므로 QR 코드는 두지 않는다. */
export function BookingDone({
  schedule,
  booking,
}: {
  schedule: Schedule;
  booking: Booking;
}) {
  const rows: [string, string][] = [
    ['일시', formatSeoul(schedule.startsAt)],
    ['좌석', booking.seats.map((seat) => seat.label).join(', ')],
    ['금액', `${booking.totalAmount.toLocaleString('ko-KR')}원`],
    ['예매 번호', String(booking.bookingId)],
  ];
  return (
    <section className="grid gap-4" aria-labelledby="booking-done-title">
      <h2 id="booking-done-title" className="text-heading font-bold text-fg">
        예매 완료
      </h2>
      <Card className="grid sm:grid-cols-[10rem_1fr]">
        <Poster
          id={schedule.id}
          title={schedule.performanceTitle}
          className="max-h-40 sm:max-h-none"
        />
        <div className="grid gap-4 border-t border-dashed border-line p-5 sm:border-t-0 sm:border-l">
          <p className="text-heading font-bold text-fg">
            {schedule.performanceTitle}
          </p>
          <dl className="m-0 grid gap-2 text-body">
            {rows.map(([term, value]) => (
              <div
                key={term}
                className="grid grid-cols-[5rem_1fr] gap-2 sm:grid-cols-[6rem_1fr]"
              >
                <dt className="text-fg-muted">{term}</dt>
                <dd className="m-0 break-words text-fg">{value}</dd>
              </div>
            ))}
          </dl>
        </div>
      </Card>
      <div className="flex flex-wrap gap-2">
        <Link href="/my" className={buttonClassName()}>
          마이페이지
        </Link>
        <Link href="/" className={buttonClassName({ variant: 'secondary' })}>
          공연 목록
        </Link>
      </div>
    </section>
  );
}
