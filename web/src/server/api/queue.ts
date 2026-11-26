import 'server-only';
import { gateway } from './gateway-client';
import { zQueueResponse } from './generated/realtime/zod.gen';
export function enterQueue(id: number) {
  return gateway.call({
    method: 'POST',
    path: `/api/v1/queues/${id}/entries`,
    schema: zQueueResponse,
    auth: 'required',
  });
}
export function getQueue(id: number) {
  return gateway.call({
    path: `/api/v1/queues/${id}/entries/me`,
    schema: zQueueResponse,
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
