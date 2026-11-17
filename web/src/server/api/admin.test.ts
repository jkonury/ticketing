import { describe, expect, it } from 'vitest';
import {
  adminMemberPageSchema,
  auditLogPageSchema,
  roleChangeSchema,
} from './admin';
import {
  couponDefinitionSchema,
  couponPageSchema,
  grantResultSchema,
  issuedCouponPageSchema,
} from './admin-coupons';

describe('admin 스키마', () => {
  it('회원 목록을 파싱한다', () => {
    const parsed = adminMemberPageSchema.parse({
      items: [
        {
          id: 1,
          email: 'a@b.c',
          name: 'a',
          role: 'ADMIN',
          createdAt: '2026-01-01T00:00:00Z',
        },
      ],
      nextCursor: null,
    });
    expect(parsed.items[0].role).toBe('ADMIN');
  });
  it('알 수 없는 역할을 거부한다', () => {
    expect(() =>
      roleChangeSchema.parse({ id: 1, email: 'a', name: 'a', role: 'ROOT' }),
    ).toThrow();
  });
  it('감사 로그를 파싱한다(actorId null 허용)', () => {
    const parsed = auditLogPageSchema.parse({
      items: [
        {
          id: 1,
          actorId: null,
          action: 'MEMBER_ROLE_CHANGED',
          targetType: 'MEMBER',
          targetId: '3',
          detail: { from: 'USER', to: 'ORGANIZER' },
          occurredAt: '2026-01-01T00:00:00Z',
        },
      ],
      nextCursor: 1,
    });
    expect(parsed.nextCursor).toBe(1);
  });
});

describe('쿠폰 스키마', () => {
  const definition = {
    id: 1,
    name: 'n',
    description: null,
    discountType: 'RATE',
    discountValue: 10,
    maxDiscount: 5000,
    minOrderAmount: 0,
    performanceId: null,
    validFrom: '2026-01-01T00:00:00Z',
    validUntil: '2026-02-01T00:00:00Z',
    issueMode: 'DIRECT',
    totalQuantity: null,
    status: 'ACTIVE',
    issueStartedAt: null,
  };
  it('목록과 stats를 파싱한다', () => {
    const parsed = couponPageSchema.parse({
      items: [
        {
          ...definition,
          stats: { issued: 1, reserved: 0, used: 2, revoked: 0 },
        },
      ],
      nextCursor: null,
    });
    expect(parsed.items[0].stats.used).toBe(2);
  });
  it('알 수 없는 발급 방식을 거부한다', () => {
    expect(() =>
      couponDefinitionSchema.parse({ ...definition, issueMode: 'X' }),
    ).toThrow();
  });
  it('발급 목록·지급 결과를 파싱한다', () => {
    expect(
      issuedCouponPageSchema.parse({
        items: [
          {
            id: 1,
            definitionId: 1,
            memberId: 2,
            status: 'EXPIRED',
            orderId: null,
            discountAmount: null,
            issuedAt: '2026-01-01T00:00:00Z',
            usedAt: null,
          },
        ],
        nextCursor: 3,
      }).nextCursor,
    ).toBe(3);
    expect(
      grantResultSchema.parse({ granted: [1], alreadyClaimed: [] }).granted,
    ).toEqual([1]);
  });
});
