import 'server-only';
import { gateway } from './gateway-client';
import {
  zBookingDetailResponse,
  zBookingPageResponse,
  zBookingResponse,
  zCreateBookingRequest,
  zPaymentRequestResponse,
  zGetScheduleAvailabilityResponse,
  zGetScheduleSalesResponse,
} from './generated/core/zod.gen';

export {
  zBookingCouponView as bookingCouponSchema,
  zBookingDetailResponse as bookingDetailSchema,
} from './generated/core/zod.gen';
export function getAvailability(ids: number[]) {
  if (!ids.length) return Promise.resolve([]);
  return gateway.call({
    path: `/api/v1/schedules/availability?scheduleIds=${ids.join(',')}`,
    schema: zGetScheduleAvailabilityResponse,
    auth: 'none',
    timeoutMs: 2000,
  });
}
export function getBookings(
  options: {
    cursor?: number;
    size?: number;
    performanceId?: number;
    timeoutMs?: number;
  } = {},
) {
  const q = new URLSearchParams();
  for (const key of ['cursor', 'size', 'performanceId'] as const) {
    const value = options[key];
    if (value !== undefined) q.set(key, String(value));
  }
  return gateway.call({
    path: `/api/v1/bookings/me?${q}`,
    schema: zBookingPageResponse,
    auth: 'required',
    timeoutMs: options.timeoutMs ?? 3000,
  });
}
export function getBooking(id: number) {
  return gateway.call({
    path: `/api/v1/bookings/${id}`,
    schema: zBookingDetailResponse,
    auth: 'required',
  });
}
export function createBooking(input: {
  scheduleId: number;
  seatIds: number[];
  admissionToken: string;
  idempotencyKey: string;
}) {
  return gateway.call({
    path: '/api/v1/bookings',
    method: 'POST',
    body: { scheduleId: input.scheduleId, seatIds: input.seatIds },
    bodySchema: zCreateBookingRequest,
    headers: {
      admissionToken: input.admissionToken,
      idempotencyKey: input.idempotencyKey,
    },
    schema: zBookingResponse,
    auth: 'required',
  });
}
export function requestPayment(id: number, idempotencyKey: string) {
  return gateway.call({
    path: `/api/v1/bookings/${id}/payments`,
    method: 'POST',
    headers: { idempotencyKey },
    schema: zPaymentRequestResponse,
    auth: 'required',
  });
}
export function cancelBooking(id: number) {
  return gateway.call({
    path: `/api/v1/bookings/${id}/cancel`,
    method: 'POST',
    schema: zBookingResponse,
    auth: 'required',
  });
}
export function getSales() {
  return gateway.call({
    path: '/api/v1/organizer/sales',
    schema: zGetScheduleSalesResponse,
    auth: 'required',
    timeoutMs: 2000,
  });
}
