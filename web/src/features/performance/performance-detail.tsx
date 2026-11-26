import Link from 'next/link';
import type { PerformanceDetailView } from '@/server/aggregate/performance-detail';
import { bookingLabel } from '@/lib/labels';
import { formatSeoul } from '@/lib/datetime';
import { Page } from '@/components/layout/page';
import { Badge } from '@/components/ui/badge';
import { Button, buttonClassName } from '@/components/ui/button';

const listBox =
  'm-0 list-none overflow-hidden rounded-card border border-line bg-surface p-0 divide-y divide-line';

export function PerformanceDetail({ view }: { view: PerformanceDetailView }) {
  return (
    <Page>
      <p className="text-micro font-extrabold tracking-widest text-brand">
        공연 상세
      </p>
      <h1 className="mt-3 text-title font-extrabold text-fg">{view.title}</h1>
      <p className="mt-3 text-body">{view.description}</p>
      <p className="mt-3 text-body text-fg-muted">{view.venue.name}</p>
      <section className="mt-10">
        <h2 className="mb-4 text-heading font-bold">가격</h2>
        <ul className={listBox}>
          {view.prices.map((price) => (
            <li key={price.grade} className="px-5 py-3">
              {price.grade}: {price.price.toLocaleString('ko-KR')}원
            </li>
          ))}
        </ul>
      </section>
      {view.mine?.status === 'unavailable' && (
        <p className="mt-3">예매 내역을 불러오지 못했습니다</p>
      )}
      {view.mine?.status === 'ok' && view.mine.data.hasBooking && (
        <p className="mt-3">
          예매 내역 있음: {view.mine.data.statuses.map(bookingLabel).join(', ')}
        </p>
      )}
      <section className="mt-10">
        <h2 className="mb-4 text-heading font-bold">회차</h2>
        <ul className={listBox}>
          {view.schedules.map((schedule) => {
            const available =
              view.availability.status === 'ok'
                ? view.availability.data.find(
                    (item) => item.scheduleId === schedule.id,
                  )
                : undefined;
            return (
              <li
                key={schedule.id}
                className="flex flex-col gap-4 p-5 sm:flex-row sm:items-center sm:justify-between"
              >
                <div>
                  <time className="font-bold">
                    {formatSeoul(schedule.startsAt)}
                  </time>
                  <p className="mt-1 text-fg-muted">
                    {available
                      ? `잔여 좌석 ${available.availableSeats} / ${available.totalSeats}`
                      : '잔여 좌석 확인 불가'}
                  </p>
                </div>
                {available?.availableSeats === 0 ? (
                  <div className="flex items-center gap-3">
                    <Badge tone="danger">매진</Badge>
                    <Button disabled>예매하기</Button>
                  </div>
                ) : (
                  <Link
                    className={buttonClassName()}
                    href={`/booking/${schedule.id}`}
                  >
                    예매하기
                  </Link>
                )}
              </li>
            );
          })}
        </ul>
      </section>
    </Page>
  );
}
