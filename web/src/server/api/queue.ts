import 'server-only';
import { z } from 'zod';
import { gateway } from './gateway-client';
const schema = z.object({
  status: z.enum(['WAITING', 'ADMITTED']),
  position: z.number().nullish(),
  admissionToken: z.string().nullish(),
  expiresAt: z.string().nullish(),
});
export function enterQueue(id: number) {
  return gateway.call({
    method: 'POST',
    path: `/api/v1/queues/${id}/entries`,
    schema,
    auth: 'required',
  });
}
export function getQueue(id: number) {
  return gateway.call({
    path: `/api/v1/queues/${id}/entries/me`,
    schema,
    auth: 'required',
  });
}
export function leaveQueue(id: number) {
  return gateway.call({
    method: 'DELETE',
    path: `/api/v1/queues/${id}/entries/me`,
    schema: null,
    auth: 'required',
  });
}
