import 'server-only';
import { z } from 'zod';
import { gateway } from './gateway-client';
export function createSseTicket() {
  return gateway.call({
    method: 'POST',
    path: '/api/v1/sse-tickets',
    schema: z.object({ ticket: z.string(), expiresAt: z.string() }),
    auth: 'required',
  });
}
