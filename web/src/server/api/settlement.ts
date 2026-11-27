import 'server-only';
import { gateway } from './gateway-client';
import { zFindResponse } from './generated/settlement/zod.gen';
export function getSettlements(range: { from: string; to: string }) {
  const query = new URLSearchParams(range);
  return gateway.call({
    path: `/api/v1/settlements?${query}`,
    schema: zFindResponse,
    auth: 'required',
    timeoutMs: 2000,
  });
}
