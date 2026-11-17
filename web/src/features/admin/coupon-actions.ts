'use server';
import { headers } from 'next/headers';
import { revalidatePath } from 'next/cache';
import { redirect } from 'next/navigation';
import { ApiError } from '@/lib/problem';
import {
  createCoupon,
  grantCoupons,
  revokeIssuedCoupon,
  stopCoupon,
  updateCoupon,
} from '@/server/api/admin-coupons';
import { assertSameOrigin } from '@/server/auth/csrf';
import { ForbiddenError, requireAdmin } from '@/server/auth/require-admin';
import { withSessionRedirect } from '@/server/auth/require-user';
import { buildCouponBody, parseMemberIds } from './coupon-input';

export type CouponActionState = { message: string };

const FORBIDDEN = '관리자만 처리할 수 있습니다. (FORBIDDEN)';
const INVALID = '입력 내용을 확인해 주세요. (INVALID_REQUEST)';
const FAILED = '요청을 처리하지 못했습니다. 다시 시도해 주세요.';
const CODE_MESSAGES: Record<string, string> = {
  COUPON_DEFINITION_LOCKED:
    '발급이 시작된 쿠폰은 이름·설명·종료일만 바꿀 수 있습니다. (COUPON_DEFINITION_LOCKED)',
  COUPON_VALID_UNTIL_SHORTENED:
    '종료일은 앞당길 수 없습니다. (COUPON_VALID_UNTIL_SHORTENED)',
  COUPON_NOT_ISSUABLE:
    '중지된 쿠폰은 발급할 수 없습니다. (COUPON_NOT_ISSUABLE)',
  COUPON_NOT_REVOCABLE:
    '사용 전(ISSUED) 쿠폰만 회수할 수 있습니다. (COUPON_NOT_REVOCABLE)',
  COUPON_NOT_FOUND: '쿠폰을 찾을 수 없습니다. (COUPON_NOT_FOUND)',
  INVALID_REQUEST: INVALID,
};

function couponErrorMessage(error: unknown) {
  if (error instanceof ApiError) {
    const mapped = error.code ? CODE_MESSAGES[error.code] : undefined;
    if (mapped) return mapped;
    if (error.status === 403) return FORBIDDEN;
  }
  return FAILED;
}

async function guard(): Promise<CouponActionState | null> {
  assertSameOrigin(new Headers(await headers()));
  try {
    await requireAdmin();
  } catch (error) {
    if (error instanceof ForbiddenError) return { message: FORBIDDEN };
    throw error;
  }
  return null;
}

function idOf(form: FormData, key: string) {
  const id = Number(form.get(key));
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

export async function createCouponAction(
  _state: CouponActionState,
  form: FormData,
): Promise<CouponActionState> {
  const denied = await guard();
  if (denied) return denied;
  const built = buildCouponBody(form);
  if (!built.ok) return { message: INVALID };
  let id: number;
  try {
    id = (await withSessionRedirect(() => createCoupon(built.body))).id;
  } catch (error) {
    return { message: couponErrorMessage(error) };
  }
  revalidatePath('/admin/coupons');
  redirect(`/admin/coupons/${id}`);
}

export async function updateCouponAction(
  _state: CouponActionState,
  form: FormData,
): Promise<CouponActionState> {
  const denied = await guard();
  if (denied) return denied;
  const id = idOf(form, 'id');
  const built = buildCouponBody(form);
  if (!id || !built.ok) return { message: INVALID };
  try {
    // PATCH의 null은 "변경 없음"이라 비워서 보낼 수 없는 필드는 뺀다.
    const body = Object.fromEntries(
      Object.entries(built.body).filter(([, value]) => value !== null),
    );
    await withSessionRedirect(() => updateCoupon(id, body));
  } catch (error) {
    return { message: couponErrorMessage(error) };
  }
  revalidatePath(`/admin/coupons/${id}`);
  revalidatePath('/admin/coupons');
  return { message: '쿠폰을 수정했습니다.' };
}

export async function stopCouponAction(
  _state: CouponActionState,
  form: FormData,
): Promise<CouponActionState> {
  const denied = await guard();
  if (denied) return denied;
  const id = idOf(form, 'id');
  if (!id) return { message: INVALID };
  try {
    await withSessionRedirect(() => stopCoupon(id));
  } catch (error) {
    return { message: couponErrorMessage(error) };
  }
  revalidatePath(`/admin/coupons/${id}`);
  revalidatePath('/admin/coupons');
  return { message: '쿠폰 발급을 중지했습니다.' };
}

export async function grantCouponsAction(
  _state: CouponActionState,
  form: FormData,
): Promise<CouponActionState> {
  const denied = await guard();
  if (denied) return denied;
  const id = idOf(form, 'id');
  const parsed = parseMemberIds(String(form.get('memberIds') ?? ''));
  if (!id) return { message: INVALID };
  if (!parsed.ok)
    return {
      message:
        parsed.reason === 'TOO_MANY'
          ? '한 번에 최대 100명까지 지급할 수 있습니다.'
          : '회원 ID를 숫자로 쉼표나 줄바꿈으로 구분해 입력해 주세요.',
    };
  try {
    const result = await withSessionRedirect(() =>
      grantCoupons(id, parsed.ids),
    );
    revalidatePath(`/admin/coupons/${id}`);
    const claimed = result.alreadyClaimed.length
      ? ` / 이미 보유: ${result.alreadyClaimed.join(', ')}`
      : '';
    return {
      message: `지급 ${result.granted.length}명${result.granted.length ? ` (${result.granted.join(', ')})` : ''}${claimed}`,
    };
  } catch (error) {
    return { message: couponErrorMessage(error) };
  }
}

export async function revokeCouponAction(
  _state: CouponActionState,
  form: FormData,
): Promise<CouponActionState> {
  const denied = await guard();
  if (denied) return denied;
  const issuedId = idOf(form, 'issuedCouponId');
  const definitionId = idOf(form, 'definitionId');
  if (!issuedId || !definitionId) return { message: INVALID };
  try {
    await withSessionRedirect(() => revokeIssuedCoupon(issuedId));
  } catch (error) {
    return { message: couponErrorMessage(error) };
  }
  revalidatePath(`/admin/coupons/${definitionId}`);
  return { message: '회수했습니다.' };
}
