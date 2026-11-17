import { seoulLocalToInstant } from '@/lib/datetime';

export const MAX_GRANT_MEMBERS = 100;

export type MemberIdParse =
  | { ok: true; ids: number[] }
  | { ok: false; reason: 'EMPTY' | 'INVALID' | 'TOO_MANY' };

/** 쉼표·공백·줄바꿈으로 구분한 회원 ID를 중복 없이 입력 순서대로 파싱한다. */
export function parseMemberIds(text: string): MemberIdParse {
  const tokens = text.split(/[\s,]+/).filter(Boolean);
  if (tokens.length === 0) return { ok: false, reason: 'EMPTY' };
  const ids: number[] = [];
  for (const token of tokens) {
    if (!/^\d+$/.test(token)) return { ok: false, reason: 'INVALID' };
    const id = Number(token);
    if (!Number.isSafeInteger(id) || id <= 0)
      return { ok: false, reason: 'INVALID' };
    if (!ids.includes(id)) ids.push(id);
  }
  if (ids.length > MAX_GRANT_MEMBERS) return { ok: false, reason: 'TOO_MANY' };
  return { ok: true, ids };
}

/** instant → `datetime-local` 입력값(Asia/Seoul). */
export function instantToSeoulLocal(value: string) {
  return new Date(new Date(value).getTime() + 9 * 3600000)
    .toISOString()
    .slice(0, 16);
}

const NUMBER_FIELDS = ['discountValue', 'minOrderAmount'] as const;
const NULLABLE_NUMBER_FIELDS = [
  'maxDiscount',
  'performanceId',
  'totalQuantity',
] as const;
const DATE_FIELDS = ['validFrom', 'validUntil'] as const;
const TEXT_FIELDS = ['discountType', 'issueMode'] as const;

export type CouponFormResult =
  { ok: true; body: Record<string, string | number | null> } | { ok: false };

/**
 * 폼 값을 API 본문으로 바꾼다. 폼에 없는 필드는 본문에서 뺀다
 * (발급 시작 후 수정 폼은 이름·설명·종료일만 보낸다).
 */
export function buildCouponBody(form: FormData): CouponFormResult {
  const body: Record<string, string | number | null> = {};
  const get = (key: string) => {
    const value = form.get(key);
    return typeof value === 'string' ? value.trim() : undefined;
  };
  const name = get('name');
  if (name !== undefined) {
    if (!name) return { ok: false };
    body.name = name;
  }
  const description = get('description');
  if (description !== undefined) body.description = description || null;
  for (const key of TEXT_FIELDS) {
    const value = get(key);
    if (value !== undefined) body[key] = value;
  }
  for (const key of NUMBER_FIELDS) {
    const value = get(key);
    if (value === undefined) continue;
    if (!/^\d+$/.test(value)) return { ok: false };
    body[key] = Number(value);
  }
  for (const key of NULLABLE_NUMBER_FIELDS) {
    const value = get(key);
    if (value === undefined) continue;
    if (value === '') body[key] = null;
    else if (/^\d+$/.test(value)) body[key] = Number(value);
    else return { ok: false };
  }
  for (const key of DATE_FIELDS) {
    const value = get(key);
    if (value === undefined) continue;
    try {
      body[key] = seoulLocalToInstant(value);
    } catch {
      return { ok: false };
    }
  }
  if (body.discountType === 'FIXED') body.maxDiscount = null;
  return { ok: true, body };
}
