import 'server-only';
// 부가: 판매 현황과 정산. 둘 다 실패하면 전체 실패.
import { getSales } from '@/server/api/booking';
import { getSettlements } from '@/server/api/settlement';
import { optional } from './section';
import { ApiError } from '@/lib/problem';
export async function getOrganizerDashboard(range: {
  from: string;
  to: string;
}) {
  const [sales, settlements] = await Promise.all([
    optional(getSales(), true),
    optional(getSettlements(range), true),
  ]);
  if (sales.status === 'unavailable' && settlements.status === 'unavailable')
    throw new ApiError(502, 'UPSTREAM_UNAVAILABLE');
  const totals = new Map<
    number,
    {
      performanceId: number;
      title: string;
      totalSeats: number;
      soldSeats: number;
      amount: number;
    }
  >();
  if (sales.status === 'ok')
    for (const item of sales.data) {
      const total = totals.get(item.performanceId) ?? {
        performanceId: item.performanceId,
        title: item.performanceTitle,
        totalSeats: 0,
        soldSeats: 0,
        amount: 0,
      };
      total.totalSeats += item.totalSeats;
      total.soldSeats += item.soldSeats;
      total.amount += item.confirmedAmount;
      totals.set(item.performanceId, total);
    }
  return {
    schedules: sales.status === 'ok' ? sales.data : [],
    sales:
      sales.status === 'ok'
        ? { status: 'ok' as const, data: Array.from(totals.values()) }
        : sales,
    settlements:
      settlements.status === 'ok'
        ? {
            status: 'ok' as const,
            data: settlements.data.map((item) => ({
              date: item.settlementDate,
              performanceId: item.performanceId,
              saleCount: item.saleCount,
              saleAmount: item.saleAmount,
              refundCount: item.refundCount,
              refundAmount: item.refundAmount,
              netAmount: item.netAmount,
              feeRate: item.feeRate,
              feeAmount: item.feeAmount,
              payoutAmount: item.payoutAmount,
            })),
          }
        : settlements,
  };
}
export type OrganizerDashboardView = Awaited<
  ReturnType<typeof getOrganizerDashboard>
>;
