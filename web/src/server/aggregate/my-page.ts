import 'server-only';
// 핵심: 예매 목록. 부가: 결제 상태.
import { getBookings } from '@/server/api/booking';
import { getPayments } from '@/server/api/payment';
import { optional } from './section';
export async function getMyPage(cursor?: number) {
  const page = await getBookings({ cursor });
  const payments = await optional(
    page.items.length
      ? getPayments(page.items.map((item) => item.bookingId))
      : Promise.resolve([]),
  );
  return {
    nextCursor: page.nextCursor ?? null,
    paymentsStatus: payments.status,
    bookings: page.items.map((item) => ({
      id: item.bookingId,
      performanceId: item.performanceId,
      title: item.performanceTitle,
      status: item.status,
      startsAt: item.startsAt,
      seatCount: item.seatCount,
      amount: item.totalAmount,
      createdAt: item.createdAt,
      payment:
        payments.status === 'ok'
          ? (payments.data.find(
              (payment) => payment.bookingId === item.bookingId,
            ) ?? null)
          : null,
    })),
  };
}
export type MyPageView = Awaited<ReturnType<typeof getMyPage>>;
