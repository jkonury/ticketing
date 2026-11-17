import 'server-only';
import { z } from 'zod';
import { gateway } from './gateway-client';

export const couponDefinitionSchema = z.object({
  id: z.number(),
  name: z.string(),
  description: z.string().nullable(),
  discountType: z.enum(['FIXED', 'RATE']),
  discountValue: z.number(),
  maxDiscount: z.number().nullable(),
  minOrderAmount: z.number(),
  performanceId: z.number().nullable(),
  validFrom: z.string(),
  validUntil: z.string(),
  issueMode: z.enum(['UNLIMITED', 'LIMITED', 'DIRECT']),
  totalQuantity: z.number().nullable(),
  status: z.enum(['ACTIVE', 'STOPPED']),
  issueStartedAt: z.string().nullable(),
});
export const couponStatsSchema = z.object({
  issued: z.number(),
  reserved: z.number(),
  used: z.number(),
  revoked: z.number(),
});
export const couponWithStatsSchema = couponDefinitionSchema.extend({
  stats: couponStatsSchema,
});
export const couponPageSchema = z.object({
  items: z.array(couponWithStatsSchema),
  nextCursor: z.number().nullable(),
});
export const issuedCouponSchema = z.object({
  id: z.number(),
  definitionId: z.number(),
  memberId: z.number(),
  status: z.enum(['ISSUED', 'RESERVED', 'USED', 'REVOKED', 'EXPIRED']),
  orderId: z.number().nullable(),
  discountAmount: z.number().nullable(),
  issuedAt: z.string(),
  usedAt: z.string().nullable(),
});
export const issuedCouponPageSchema = z.object({
  items: z.array(issuedCouponSchema),
  nextCursor: z.number().nullable(),
});
export const grantResultSchema = z.object({
  granted: z.array(z.number()),
  alreadyClaimed: z.array(z.number()),
});

export type CouponWithStats = z.infer<typeof couponWithStatsSchema>;
export type CouponBody = Record<string, string | number | null | undefined>;

function toQuery(params: Record<string, string | number | undefined>) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params))
    if (value !== undefined && value !== '') query.set(key, String(value));
  const text = query.toString();
  return text ? `?${text}` : '';
}

export function listAdminCoupons(params: { cursor?: number; size?: number }) {
  return gateway.call({
    path: `/api/v1/admin/coupons${toQuery(params)}`,
    schema: couponPageSchema,
    auth: 'required',
  });
}
export function getAdminCoupon(id: number) {
  return gateway.call({
    path: `/api/v1/admin/coupons/${id}`,
    schema: couponWithStatsSchema,
    auth: 'required',
  });
}
export function createCoupon(body: CouponBody) {
  return gateway.call({
    method: 'POST',
    path: '/api/v1/admin/coupons',
    body,
    schema: couponDefinitionSchema,
    auth: 'required',
  });
}
export function updateCoupon(id: number, body: CouponBody) {
  return gateway.call({
    method: 'PATCH',
    path: `/api/v1/admin/coupons/${id}`,
    body,
    schema: couponDefinitionSchema,
    auth: 'required',
  });
}
export function stopCoupon(id: number) {
  return gateway.call({
    method: 'POST',
    path: `/api/v1/admin/coupons/${id}/stop`,
    schema: couponDefinitionSchema,
    auth: 'required',
  });
}
export function listIssuedCoupons(
  id: number,
  params: { cursor?: number; size?: number; status?: string },
) {
  return gateway.call({
    path: `/api/v1/admin/coupons/${id}/issued${toQuery(params)}`,
    schema: issuedCouponPageSchema,
    auth: 'required',
  });
}
export function grantCoupons(id: number, memberIds: number[]) {
  return gateway.call({
    method: 'POST',
    path: `/api/v1/admin/coupons/${id}/grants`,
    body: { memberIds },
    schema: grantResultSchema,
    auth: 'required',
  });
}
export function revokeIssuedCoupon(issuedCouponId: number) {
  return gateway.call({
    method: 'POST',
    path: `/api/v1/admin/issued-coupons/${issuedCouponId}/revoke`,
    schema: issuedCouponSchema,
    auth: 'required',
  });
}
