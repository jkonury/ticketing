import { z } from 'zod';

export type Seat = {
  seatId: number;
  section: string;
  row: string;
  number: number;
  grade: string;
  price: number;
};
export type Schedule = {
  id: number;
  performanceTitle: string;
  startsAt: string;
};
export type Admission = { admissionToken: string; expiresAt: string };
export type Booking = {
  bookingId: number;
  expiresAt: string;
  serverTime: string;
  receivedAt: number;
  totalAmount: number;
  seats: { seatId: number; label: string; price: number }[];
};

export const admissionSchema = z.object({
  admissionToken: z.string(),
  expiresAt: z.string(),
});
export const queueSchema = z.object({
  status: z.enum(['WAITING', 'ADMITTED']),
  position: z.number().nullish(),
  admissionToken: z.string().nullish(),
  expiresAt: z.string().nullish(),
});
export const bookingSchema = z.object({
  bookingId: z.number(),
  expiresAt: z.string(),
  serverTime: z.string().datetime({ offset: true }),
  totalAmount: z.number(),
  seats: z.array(
    z.object({ seatId: z.number(), label: z.string(), price: z.number() }),
  ),
});
export const errorSchema = z.object({
  code: z.string(),
  seatIds: z.array(z.number()).optional(),
});
export const statusSchema = z.enum(['AVAILABLE', 'HELD', 'SOLD']);
export type SeatStatus = z.infer<typeof statusSchema>;
export const snapshotSchema = z.object({
  seats: z.array(z.object({ seatId: z.number(), status: statusSchema })),
});
export const changeSchema = z.object({
  seatIds: z.array(z.number()),
  status: statusSchema,
});

export async function call(path: string, method: string, body?: unknown) {
  const response = await fetch(path, {
    method,
    ...(body === undefined
      ? {}
      : {
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        }),
  });
  const data: unknown = response.status === 204 ? null : await response.json();
  if (!response.ok) throw errorSchema.parse(data);
  return data;
}
export function codeOf(error: unknown) {
  const result = errorSchema.safeParse(error);
  return result.success ? result.data.code : 'UNKNOWN';
}
