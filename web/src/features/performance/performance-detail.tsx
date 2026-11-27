import Link from 'next/link';
import type { PerformanceDetailView } from '@/server/aggregate/performance-detail';
import { bookingLabel } from '@/lib/labels';
import { formatSeoul } from '@/lib/datetime';
import { Page } from '@/components/layout/page';
import { Badge } from '@/components/ui/badge';
import { Button, buttonClassName } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Poster } from '@/components/ui/poster';
import { genreLabels } from '@/lib/performance-genre';

export function PerformanceDetail({ view }: { view: PerformanceDetailView }) {
  return (
    <Page>
      <div className="grid gap-6 sm:grid-cols-[240px_1fr] sm:gap-8 lg:grid-cols-[300px_1fr]">
        <Card
          padding="none"
          className="w-full max-w-60 self-start sm:max-w-none"
        >
          <Poster id={view.id} title={view.title} imageUrl={view.posterUrl} />
        </Card>
        <div className="min-w-0">
          {view.genre && (
            <Badge className="mb-2">{genreLabels[view.genre]}</Badge>
          )}
          <h1 className="text-title font-extrabold text-fg">{view.title}</h1>
          <p className="mt-3 text-body text-fg-muted">{view.venue.name}</p>
          <p className="mt-4 whitespace-pre-line text-body">
            {view.description}
          </p>
          {view.mine?.status === 'unavailable' && (
            <p className="mt-4 rounded-control bg-warning-soft px-4 py-3 text-caption text-warning-strong">
              예매 내역을 불러오지 못했어요
            </p>
          )}
          {view.mine?.status === 'ok' && view.mine.data.hasBooking && (
            <p className="mt-4 rounded-control bg-brand-soft px-4 py-3 text-caption text-brand-strong">
              예매 내역 있음:{' '}
              {view.mine.data.statuses.map(bookingLabel).join(', ')}
            </p>
          )}
          <section className="mt-6">
            <h2 className="mb-3 text-heading font-bold">가격</h2>
            <Card>
              <ul className="m-0 list-none divide-y divide-line p-0">
                {view.prices.map((price) => (
                  <li
                    key={price.grade}
                    className="flex items-center justify-between gap-4 px-4 py-3"
                  >
                    <span className="text-fg-muted">{price.grade}</span>
                    <span className="font-bold">
                      {price.price.toLocaleString('ko-KR')}원
                    </span>
                  </li>
                ))}
              </ul>
            </Card>
          </section>
        </div>
      </div>
      <section className="mt-10">
        <h2 className="mb-4 text-heading font-bold">회차</h2>
        <ul className="m-0 grid list-none gap-3 p-0">
          {view.schedules.map((schedule) => {
            const available =
              view.availability.status === 'ok'
                ? view.availability.data.find(
                    (item) => item.scheduleId === schedule.id,
                  )
                : undefined;
            return (
              <li key={schedule.id}>
                <Card
                  padding="md"
                  className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between"
                >
                  <div>
                    <time className="font-bold">
                      {formatSeoul(schedule.startsAt)}
                    </time>
                    <p className="mt-1 text-caption text-fg-muted">
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
                </Card>
              </li>
            );
          })}
        </ul>
      </section>
    </Page>
  );
}
