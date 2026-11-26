import 'server-only';
import type { z } from 'zod';
import { gateway } from './gateway-client';
import {
  zAdminCouponPageResponse,
  zAdminCouponResponse,
  zCouponDefinitionView,
  zCreateCouponRequest,
  zCursorPageIssuedCouponView,
  zGrantCouponRequest,
  zGrantResult,
  zIssuedCouponView,
  zUpdateCouponRequest,
} from './generated/core/zod.gen';

export {
  zAdminCouponPageResponse as couponPageSchema,
  zAdminCouponResponse as couponWithStatsSchema,
  zCouponDefinitionView as couponDefinitionSchema,
  zCouponStats as couponStatsSchema,
  zCursorPageIssuedCouponView as issuedCouponPageSchema,
  zGrantResult as grantResultSchema,
  zIssuedCouponView as issuedCouponSchema,
} from './generated/core/zod.gen';

export type CouponWithStats = z.infer<typeof zAdminCouponResponse>;
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
    schema: zAdminCouponPageResponse,
    auth: 'required',
  });
}
export function getAdminCoupon(id: number) {
  return gateway.call({
    path: `/api/v1/admin/coupons/${id}`,
    schema: zAdminCouponResponse,
    auth: 'required',
  });
}
export function createCoupon(body: CouponBody) {
  return gateway.call({
    method: 'POST',
    path: '/api/v1/admin/coupons',
    body,
    bodySchema: zCreateCouponRequest,
    schema: zCouponDefinitionView,
    auth: 'required',
  });
}
export function updateCoupon(id: number, body: CouponBody) {
  return gateway.call({
    method: 'PATCH',
    path: `/api/v1/admin/coupons/${id}`,
    body,
    bodySchema: zUpdateCouponRequest,
    schema: zCouponDefinitionView,
    auth: 'required',
  });
}
export function stopCoupon(id: number) {
  return gateway.call({
    method: 'POST',
    path: `/api/v1/admin/coupons/${id}/stop`,
    schema: zCouponDefinitionView,
    auth: 'required',
  });
}
export function listIssuedCoupons(
  id: number,
  params: { cursor?: number; size?: number; status?: string },
) {
  return gateway.call({
    path: `/api/v1/admin/coupons/${id}/issued${toQuery(params)}`,
    schema: zCursorPageIssuedCouponView,
    auth: 'required',
  });
}
export function grantCoupons(id: number, memberIds: number[]) {
  return gateway.call({
    method: 'POST',
    path: `/api/v1/admin/coupons/${id}/grants`,
    body: { memberIds },
    bodySchema: zGrantCouponRequest,
    schema: zGrantResult,
    auth: 'required',
  });
}
export function revokeIssuedCoupon(issuedCouponId: number) {
  return gateway.call({
    method: 'POST',
    path: `/api/v1/admin/issued-coupons/${issuedCouponId}/revoke`,
    schema: zIssuedCouponView,
    auth: 'required',
  });
}
