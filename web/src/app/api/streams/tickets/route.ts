import { z } from 'zod';
import { assertSameOrigin } from '@/server/auth/csrf';
import { createSseTicket } from '@/server/api/member';
import { errorResponse, positiveId } from '@/server/api/route-utils';
import { env } from '@/server/env';
const input = z.object({
  kind: z.enum(['queue', 'seats']),
  scheduleId: positiveId,
  lastEventId: z.string().optional(),
});
export async function POST(request: Request) {
  try {
    assertSameOrigin(request.headers);
    const data = input.parse(await request.json());
    const ticket = await createSseTicket();
    const path =
      data.kind === 'queue'
        ? `/api/v1/streams/queues/${data.scheduleId}`
        : `/api/v1/streams/schedules/${data.scheduleId}/seats`;
    const url = new URL(path, env.GATEWAY_PUBLIC_URL);
    url.searchParams.set('ticket', ticket.ticket);
    if (data.kind === 'seats' && data.lastEventId)
      url.searchParams.set('lastEventId', data.lastEventId);
    return Response.json({ url: url.toString(), expiresAt: ticket.expiresAt });
  } catch (error) {
    return errorResponse(error);
  }
}
