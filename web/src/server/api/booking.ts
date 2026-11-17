import 'server-only';
import { z } from 'zod';
import { gateway } from './gateway-client';
const seat = z.object({
  seatId: z.number(),
  label: z.string(),
  price: z.number(),
});
const summary = z.object({
  bookingId: z.number(),
  status: z.string(),
  performanceId: z.number(),
  performanceTitle: z.string(),
  startsAt: z.string(),
  seatCount: z.number(),
  totalAmount: z.number(),
  createdAt: z.string(),
});
const page = z.object({
  items: z.array(summary),
  nextCursor: z.number().nullish(),
});
const booking = z.object({
  bookingId: z.number(),
  status: z.string(),
  totalAmount: z.number(),
  expiresAt: z.string(),
  seats: z.array(seat),
});
export const bookingCouponSchema = z.object({
  issuedCouponId: z.number(),
  state: z.enum(['RESERVING', 'RESERVED', 'REJECTED']),
  discountAmount: z.number().nullish(),
  rejectReason: z.string().nullish(),
});
export const bookingDetailSchema = booking.extend({
  performanceTitle: z.string(),
  startsAt: z.string(),
  createdAt: z.string(),
  listAmount: z.number().optional(),
  discountAmount: z.number().optional(),
  payableAmount: z.number().optional(),
  coupon: bookingCouponSchema.nullish(),
});
const detail = bookingDetailSchema;
const availability = z.array(
  z.object({
    scheduleId: z.number(),
    totalSeats: z.number(),
    availableSeats: z.number(),
  }),
);
const sales = z.array(
  z.object({
    performanceId: z.number(),
    performanceTitle: z.string(),
    scheduleId: z.number(),
    startsAt: z.string(),
    totalSeats: z.number(),
    soldSeats: z.number(),
    confirmedAmount: z.number(),
  }),
);
export function getAvailability(ids: number[]) {
  if (!ids.length) return Promise.resolve([]);
  return gateway.call({
    path: `/api/v1/schedules/availability?scheduleIds=${ids.join(',')}`,
    schema: availability,
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
    schema: page,
    auth: 'required',
    timeoutMs: options.timeoutMs ?? 3000,
  });
}
export function getBooking(id: number) {
  return gateway.call({
    path: `/api/v1/bookings/${id}`,
    schema: detail,
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
    headers: {
      admissionToken: input.admissionToken,
      idempotencyKey: input.idempotencyKey,
    },
    schema: booking,
    auth: 'required',
  });
}
export function requestPayment(id: number, idempotencyKey: string) {
  return gateway.call({
    path: `/api/v1/bookings/${id}/payments`,
    method: 'POST',
    headers: { idempotencyKey },
    schema: z.object({ bookingId: z.number(), status: z.string() }),
    auth: 'required',
  });
}
export function cancelBooking(id: number) {
  return gateway.call({
    path: `/api/v1/bookings/${id}/cancel`,
    method: 'POST',
    schema: booking,
    auth: 'required',
  });
}
export function getSales() {
  return gateway.call({
    path: '/api/v1/organizer/sales',
    schema: sales,
    auth: 'required',
    timeoutMs: 2000,
  });
}
