import 'server-only';
import { z } from 'zod';
import { gateway } from './gateway-client';
const schema = z.array(
  z.object({
    settlementDate: z.string(),
    performanceId: z.number(),
    saleCount: z.number(),
    saleAmount: z.number(),
    refundCount: z.number(),
    refundAmount: z.number(),
    netAmount: z.number(),
    feeRate: z.number(),
    feeAmount: z.number(),
    payoutAmount: z.number(),
  }),
);
export function getSettlements(range: { from: string; to: string }) {
  const query = new URLSearchParams(range);
  return gateway.call({
    path: `/api/v1/settlements?${query}`,
    schema,
    auth: 'required',
    timeoutMs: 2000,
  });
}
