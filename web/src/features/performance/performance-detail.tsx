import Link from 'next/link';
import type { PerformanceDetailView } from '@/server/aggregate/performance-detail';
import { bookingLabel } from '@/lib/labels';
import { formatSeoul } from '@/lib/datetime';
export function PerformanceDetail({ view }: { view: PerformanceDetailView }) {
  return (
    <main>
      <p className="eyebrow">공연 상세</p>
      <h1>{view.title}</h1>
      <p>{view.description}</p>
      <p className="detail-venue">{view.venue.name}</p>
      <section className="detail-section">
        <h2>가격</h2>
        <ul className="price-list">
          {view.prices.map((price) => (
            <li key={price.grade}>
              {price.grade}: {price.price.toLocaleString('ko-KR')}원
            </li>
          ))}
        </ul>
      </section>
      {view.mine?.status === 'unavailable' && (
        <p>예매 내역을 불러오지 못했습니다</p>
      )}
      {view.mine?.status === 'ok' && view.mine.data.hasBooking && (
        <p>
          예매 내역 있음: {view.mine.data.statuses.map(bookingLabel).join(', ')}
        </p>
      )}
      <section className="detail-section">
        <h2>회차</h2>
        <ul className="schedule-list">
          {view.schedules.map((schedule) => {
            const available =
              view.availability.status === 'ok'
                ? view.availability.data.find(
                    (item) => item.scheduleId === schedule.id,
                  )
                : undefined;
            return (
              <li key={schedule.id}>
                <div>
                  <time>{formatSeoul(schedule.startsAt)}</time>
                  <p>
                    {available
                      ? `잔여 좌석 ${available.availableSeats} / ${available.totalSeats}`
                      : '잔여 좌석 확인 불가'}
                  </p>
                </div>
                {available?.availableSeats === 0 ? (
                  <>
                    <span>매진</span>
                    <button disabled>예매하기</button>
                  </>
                ) : (
                  <Link className="button" href={`/booking/${schedule.id}`}>
                    예매하기
                  </Link>
                )}
              </li>
            );
          })}
        </ul>
      </section>
    </main>
  );
}
