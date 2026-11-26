import 'server-only';
import { gateway } from './gateway-client';
import { zMineResponse } from './generated/payment/zod.gen';
export function getPayments(ids: number[]) {
  return gateway.call({
    path: `/api/v1/payments/me?bookingIds=${ids.join(',')}`,
    schema: zMineResponse,
    auth: 'required',
    timeoutMs: 2000,
  });
}
