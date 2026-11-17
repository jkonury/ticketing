import { z } from 'zod';

export const POLL_INTERVAL_MS = 1000;
export const POLL_MAX_ATTEMPTS = 15;

export const couponStateSchema = z.object({
  issuedCouponId: z.number(),
  state: z.enum(['RESERVING', 'RESERVED', 'REJECTED']),
  discountAmount: z.number().nullish(),
  rejectReason: z.string().nullish(),
});
export const amountSchema = z.object({
  status: z.string().optional(),
  listAmount: z.number().optional(),
  discountAmount: z.number().optional(),
  payableAmount: z.number().optional(),
  coupon: couponStateSchema.nullish(),
});
export type AmountView = z.infer<typeof amountSchema>;
export const myCouponsSchema = z.array(
  z.object({
    id: z.number(),
    status: z.string(),
    definition: z.object({
      name: z.string(),
      validFrom: z.string(),
      validUntil: z.string(),
      performanceId: z.number().nullable(),
    }),
  }),
);
export type MyCoupon = z.infer<typeof myCouponsSchema>[number];
