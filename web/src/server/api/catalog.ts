import 'server-only';
import { gateway } from './gateway-client';
import {
  zIdResponse,
  zPerformanceDetail,
  zPerformancePage,
  zRegisterPerformanceRequest,
  zRegisterScheduleRequest,
  zRegisterVenueRequest,
  zScheduleDetail,
  zSeatsResponse,
  zVenueRegisteredResponse,
} from './generated/core/zod.gen';

export { zPerformanceDetail as performanceSchema };
export function getPerformances(cursor?: number, size?: number) {
  const query = new URLSearchParams();
  if (cursor !== undefined) query.set('cursor', String(cursor));
  if (size !== undefined) query.set('size', String(size));
  return gateway.call({
    path: `/api/v1/performances?${query}`,
    schema: zPerformancePage,
    auth: 'none',
    cache: { revalidateSeconds: 30 },
  });
}
export function getPerformance(id: number) {
  return gateway.call({
    path: `/api/v1/performances/${id}`,
    schema: zPerformanceDetail,
    auth: 'none',
    cache: { revalidateSeconds: 30 },
    timeoutMs: 3000,
  });
}
export function getSeats(id: number) {
  return gateway.call({
    path: `/api/v1/schedules/${id}/seats`,
    schema: zSeatsResponse,
    auth: 'none',
  });
}
export function getSchedule(id: number) {
  return gateway.call({
    path: `/api/v1/schedules/${id}`,
    schema: zScheduleDetail,
    auth: 'none',
  });
}
export function createVenue(body: unknown) {
  return gateway.call({
    path: '/api/v1/venues',
    method: 'POST',
    body,
    bodySchema: zRegisterVenueRequest,
    schema: zVenueRegisteredResponse,
    auth: 'required',
  });
}
export function createPerformance(body: unknown) {
  return gateway.call({
    path: '/api/v1/performances',
    method: 'POST',
    body,
    bodySchema: zRegisterPerformanceRequest,
    schema: zIdResponse,
    auth: 'required',
  });
}
export function createSchedule(id: number, body: unknown) {
  return gateway.call({
    path: `/api/v1/performances/${id}/schedules`,
    method: 'POST',
    body,
    bodySchema: zRegisterScheduleRequest,
    schema: zIdResponse,
    auth: 'required',
  });
}
