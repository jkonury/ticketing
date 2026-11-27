import 'server-only';
import { z } from 'zod';
import type { PerformanceGenre } from '@/lib/performance-genre';
import { gateway } from './gateway-client';
import {
  zIdResponse,
  zPerformanceDetailResponse,
  zPerformancePageResponse,
  zPerformanceSummaryResponse,
  zPosterUploadedResponse,
  zPosterUploadRequest,
  zRegisterPerformanceRequest,
  zRegisterScheduleRequest,
  zRegisterVenueRequest,
  zScheduleDetail,
  zSeatsResponse,
  zUpload,
  zVenueRegisteredResponse,
} from './generated/core/zod.gen';

const posterUrl = z.url({ protocol: /^https?$/ });
const posterFields = {
  posterUrl: posterUrl.nullish(),
  // 생성기가 nullable 열거형을 optional로 만들므로 기존 null 응답도 허용한다.
  genre: zPerformanceSummaryResponse.shape.genre.nullish(),
};
export const performanceSchema =
  zPerformanceDetailResponse.extend(posterFields);
const pageSchema = zPerformancePageResponse.extend({
  items: z.array(zPerformanceSummaryResponse.extend(posterFields)),
});
const registerPerformanceSchema = zRegisterPerformanceRequest.extend({
  genre: zRegisterPerformanceRequest.shape.genre.nullish(),
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
    bodySchema: registerPerformanceSchema,
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

export function issuePosterUpload(id: number, contentType: string) {
  return gateway.call({
    path: `/api/v1/performances/${id}/poster-uploads`,
    method: 'POST',
    body: { contentType },
    bodySchema: zPosterUploadRequest,
    schema: zUpload.extend({ url: posterUrl }),
    auth: 'required',
  });
}

export function completePosterUpload(id: number, uploadId: string) {
  return gateway.call({
    path: `/api/v1/performances/${id}/poster-uploads/${uploadId}/complete`,
    method: 'POST',
    schema: zPosterUploadedResponse.extend({ posterUrl }),
    auth: 'required',
    timeoutMs: 10000,
  });
}
