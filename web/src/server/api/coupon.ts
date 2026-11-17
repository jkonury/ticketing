import 'server-only';
import { z } from 'zod';
import { gateway } from './gateway-client';
import { couponDefinitionSchema, issuedCouponSchema } from './admin-coupons';
import { bookingCouponSchema, bookingDetailSchema } from './booking';

export const claimableCouponSchema = couponDefinitionSchema.extend({
  claimed: z.boolean(),
});
export const myCouponSchema = issuedCouponSchema.extend({
  definition: couponDefinitionSchema,
});
export { bookingCouponSchema, bookingDetailSchema };

export function listClaimableCoupons() {
  return gateway.call({
    path: '/api/v1/coupons',
    schema: z.array(claimableCouponSchema),
    auth: 'required',
  });
}
export function claimCoupon(definitionId: number) {
  return gateway.call({
    method: 'POST',
    path: `/api/v1/coupons/${definitionId}/claims`,
    schema: issuedCouponSchema,
    auth: 'required',
  });
}
export function listMyCoupons(timeoutMs?: number) {
  return gateway.call({
    path: '/api/v1/me/coupons',
    schema: z.array(myCouponSchema),
    auth: 'required',
    ...(timeoutMs ? { timeoutMs } : {}),
  });
}
export function applyBookingCoupon(bookingId: number, issuedCouponId: number) {
  return gateway.call({
    method: 'PUT',
    path: `/api/v1/bookings/${bookingId}/coupon`,
    body: { issuedCouponId },
    schema: bookingDetailSchema,
    auth: 'required',
  });
}
export function removeBookingCoupon(bookingId: number) {
  return gateway.call({
    method: 'DELETE',
    path: `/api/v1/bookings/${bookingId}/coupon`,
    schema: bookingDetailSchema,
    auth: 'required',
  });
}
