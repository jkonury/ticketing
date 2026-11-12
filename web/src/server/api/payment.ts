import 'server-only';
import { z } from 'zod';
import { gateway } from './gateway-client';
const schema = z.array(
  z.object({
    bookingId: z.number(),
    status: z.string(),
    failureReason: z.string().nullish(),
    approvedAt: z.string().nullish(),
    refundedAt: z.string().nullish(),
  }),
);
export function getPayments(ids: number[]) {
  return gateway.call({
    path: `/api/v1/payments/me?bookingIds=${ids.join(',')}`,
    schema,
    auth: 'required',
    timeoutMs: 2000,
  });
}
