import { describe, expect, it } from 'vitest';
import {
  buildCouponBody,
  instantToSeoulLocal,
  parseMemberIds,
} from './coupon-input';

describe('회원 ID 입력 파싱', () => {
  it('쉼표·공백·줄바꿈을 구분자로 보고 중복을 제거한다', () => {
    expect(parseMemberIds('1, 2\n3  2,,1')).toEqual({
      ok: true,
      ids: [1, 2, 3],
    });
  });
  it('빈 입력은 EMPTY', () => {
    expect(parseMemberIds(' \n, ')).toEqual({ ok: false, reason: 'EMPTY' });
  });
  it('숫자가 아니거나 0·음수·소수면 INVALID', () => {
    for (const text of ['1,a', '0', '-3', '1.5', '1e3'])
      expect(parseMemberIds(text)).toEqual({ ok: false, reason: 'INVALID' });
  });
  it('100명을 넘으면 TOO_MANY', () => {
    const ids = Array.from({ length: 101 }, (_, i) => i + 1).join(',');
    expect(parseMemberIds(ids)).toEqual({ ok: false, reason: 'TOO_MANY' });
  });
});

describe('쿠폰 폼 본문 변환', () => {
  it('RATE 생성 폼을 변환하고 KST를 instant로 바꾼다', () => {
    const form = new FormData();
    form.set('name', ' 10% ');
    form.set('description', '');
    form.set('discountType', 'RATE');
    form.set('discountValue', '10');
    form.set('maxDiscount', '5000');
    form.set('minOrderAmount', '0');
    form.set('performanceId', '');
    form.set('validFrom', '2026-01-01T09:00');
    form.set('validUntil', '2026-02-01T09:00');
    form.set('issueMode', 'DIRECT');
    form.set('totalQuantity', '');
    expect(buildCouponBody(form)).toEqual({
      ok: true,
      body: {
        name: '10%',
        description: null,
        discountType: 'RATE',
        discountValue: 10,
        maxDiscount: 5000,
        minOrderAmount: 0,
        performanceId: null,
        validFrom: '2026-01-01T00:00:00.000Z',
        validUntil: '2026-02-01T00:00:00.000Z',
        issueMode: 'DIRECT',
        totalQuantity: null,
      },
    });
  });
  it('FIXED면 maxDiscount를 null로 보낸다', () => {
    const form = new FormData();
    form.set('discountType', 'FIXED');
    form.set('maxDiscount', '100');
    expect(buildCouponBody(form)).toEqual({
      ok: true,
      body: { discountType: 'FIXED', maxDiscount: null },
    });
  });
  it('잠긴 수정 폼은 이름·설명·종료일만 보낸다', () => {
    const form = new FormData();
    form.set('name', 'a');
    form.set('description', 'd');
    form.set('validUntil', '2026-02-01T09:00');
    const result = buildCouponBody(form);
    expect(result.ok && Object.keys(result.body).sort()).toEqual([
      'description',
      'name',
      'validUntil',
    ]);
  });
  it('잘못된 숫자·날짜·빈 이름은 거부한다', () => {
    for (const [key, value] of [
      ['discountValue', '-1'],
      ['maxDiscount', 'x'],
      ['validFrom', '2026-13-01T00:00'],
      ['name', ' '],
    ]) {
      const form = new FormData();
      form.set(key, value);
      expect(buildCouponBody(form)).toEqual({ ok: false });
    }
  });
  it('instant를 KST 입력값으로 되돌린다', () => {
    expect(instantToSeoulLocal('2026-01-01T00:00:00Z')).toBe(
      '2026-01-01T09:00',
    );
  });
});
