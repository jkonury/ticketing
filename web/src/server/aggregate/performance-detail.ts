import 'server-only';
// 핵심: 공연 정보. 부가: 잔여 좌석과 내 예매.
import { getPerformance } from '@/server/api/catalog';
import { getAvailability, getBookings } from '@/server/api/booking';
import { getCurrentUser } from '@/server/auth/current-user';
import { optional } from './section';
export async function getPerformanceDetail(id: number) {
  const performance = await getPerformance(id);
  const [availability, mine] = await Promise.all([
    optional(getAvailability(performance.schedules.map((item) => item.id))),
    (async () => {
      const user = await getCurrentUser();
      if (!user || user.role !== 'USER') return null;
      return optional(getBookings({ performanceId: id, timeoutMs: 2000 }));
    })(),
  ]);
  return {
    id: performance.id,
    title: performance.title,
    description: performance.description,
    genre: performance.genre,
    posterUrl: performance.posterUrl,
    venue: { id: performance.venueId, name: performance.venueName },
    prices: performance.prices.map((item) => ({
      grade: item.grade,
      price: item.price,
    })),
    schedules: performance.schedules.map((item) => ({
      id: item.id,
      startsAt: item.startsAt,
    })),
    availability,
    mine:
      mine &&
      (mine.status === 'ok'
        ? {
            status: 'ok' as const,
            data: {
              hasBooking: mine.data.items.length > 0,
              statuses: mine.data.items.map((item) => item.status),
            },
          }
        : mine),
  };
}
export type PerformanceDetailView = Awaited<
  ReturnType<typeof getPerformanceDetail>
>;
