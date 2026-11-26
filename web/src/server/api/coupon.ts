import 'server-only';
import { gateway } from './gateway-client';
import {
  zApplyCouponRequest,
  zBookingDetailResponse,
  zClaimableResponse,
  zIssuedCouponView,
  zMyCouponsResponse,
} from './generated/core/zod.gen';
export {
  zClaimableCouponResponse as claimableCouponSchema,
  zMyCouponResponse as myCouponSchema,
} from './generated/core/zod.gen';
export { bookingCouponSchema, bookingDetailSchema } from './booking';

export function listClaimableCoupons() {
  return gateway.call({
    path: '/api/v1/coupons',
    schema: zClaimableResponse,
    auth: 'required',
  });
}
export function claimCoupon(definitionId: number) {
  return gateway.call({
    method: 'POST',
    path: `/api/v1/coupons/${definitionId}/claims`,
    schema: zIssuedCouponView,
    auth: 'required',
  });
}
export function listMyCoupons(timeoutMs?: number) {
  return gateway.call({
    path: '/api/v1/me/coupons',
    schema: zMyCouponsResponse,
    auth: 'required',
    ...(timeoutMs ? { timeoutMs } : {}),
  });
}
export function applyBookingCoupon(bookingId: number, issuedCouponId: number) {
  return gateway.call({
    method: 'PUT',
    path: `/api/v1/bookings/${bookingId}/coupon`,
    body: { issuedCouponId },
    bodySchema: zApplyCouponRequest,
    schema: zBookingDetailResponse,
    auth: 'required',
  });
}
export function removeBookingCoupon(bookingId: number) {
  return gateway.call({
    method: 'DELETE',
    path: `/api/v1/bookings/${bookingId}/coupon`,
    schema: zBookingDetailResponse,
    auth: 'required',
  });
}
