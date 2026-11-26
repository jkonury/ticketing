import 'server-only';
import { z } from 'zod';
import {
  performanceGenre,
  type PerformanceGenre,
} from '@/lib/performance-genre';
import { gateway } from './gateway-client';

const price = z.object({ grade: z.string(), price: z.number() });
const schedule = z.object({ id: z.number(), startsAt: z.string() });
const posterFields = {
  genre: performanceGenre.nullish(),
  posterUrl: z.url({ protocol: /^https?$/ }).nullish(),
};
export const performanceSchema = z.object({
  id: z.number(),
  title: z.string(),
  description: z.string(),
  venueId: z.number(),
  venueName: z.string(),
  prices: z.array(price),
  schedules: z.array(schedule),
  ...posterFields,
});
const pageSchema = z.object({
  items: z.array(
    z.object({
      id: z.number(),
      title: z.string(),
      venueName: z.string(),
      firstStartsAt: z.string().nullish(),
      ...posterFields,
    }),
  ),
  nextCursor: z.number().nullish(),
});
const seatsSchema = z.array(
  z.object({
    seatId: z.number(),
    section: z.string(),
    row: z.string(),
    number: z.number(),
    grade: z.string(),
    price: z.number(),
  }),
);
const registeredSchema = z.object({
  id: z.number(),
  seatCount: z.number().optional(),
});
export function getPerformances(
  cursor?: number,
  size?: number,
  genre?: PerformanceGenre,
) {
  const query = new URLSearchParams();
  if (cursor !== undefined) query.set('cursor', String(cursor));
  if (size !== undefined) query.set('size', String(size));
  if (genre !== undefined) query.set('genre', genre);
  return gateway.call({
    path: `/api/v1/performances?${query}`,
    schema: pageSchema,
    auth: 'none',
    cache: { revalidateSeconds: 30 },
  });
}
export function getPerformance(id: number) {
  return gateway.call({
    path: `/api/v1/performances/${id}`,
    schema: performanceSchema,
    auth: 'none',
    cache: { revalidateSeconds: 30 },
    timeoutMs: 3000,
  });
}
export function getSeats(id: number) {
  return gateway.call({
    path: `/api/v1/schedules/${id}/seats`,
    schema: seatsSchema,
    auth: 'none',
  });
}
export function getSchedule(id: number) {
  return gateway.call({
    path: `/api/v1/schedules/${id}`,
    schema: z.object({
      id: z.number(),
      performanceId: z.number(),
      performanceTitle: z.string(),
      startsAt: z.string(),
    }),
    auth: 'none',
  });
}
export function createVenue(body: unknown) {
  return gateway.call({
    path: '/api/v1/venues',
    method: 'POST',
    body,
    schema: registeredSchema,
    auth: 'required',
  });
}
export function createPerformance(body: unknown) {
  return gateway.call({
    path: '/api/v1/performances',
    method: 'POST',
    body,
    schema: registeredSchema,
    auth: 'required',
  });
}
export function createSchedule(id: number, body: unknown) {
  return gateway.call({
    path: `/api/v1/performances/${id}/schedules`,
    method: 'POST',
    body,
    schema: registeredSchema,
    auth: 'required',
  });
}

export function issuePosterUpload(id: number, contentType: string) {
  return gateway.call({
    path: `/api/v1/performances/${id}/poster-uploads`,
    method: 'POST',
    body: { contentType },
    schema: z.object({
      uploadId: z.uuid(),
      url: z.url({ protocol: /^https?$/ }),
      headers: z.record(z.string(), z.string()),
    }),
    auth: 'required',
  });
}

export function completePosterUpload(id: number, uploadId: string) {
  return gateway.call({
    path: `/api/v1/performances/${id}/poster-uploads/${uploadId}/complete`,
    method: 'POST',
    schema: z.object({ posterUrl: z.url({ protocol: /^https?$/ }) }),
    auth: 'required',
    timeoutMs: 10000,
  });
}
