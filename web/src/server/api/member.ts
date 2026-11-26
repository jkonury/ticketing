import 'server-only';
import { gateway } from './gateway-client';
import { zSseTicketResponse } from './generated/core/zod.gen';
export function createSseTicket() {
  return gateway.call({
    method: 'POST',
    path: '/api/v1/sse-tickets',
    schema: zSseTicketResponse,
    auth: 'required',
  });
}
