import { describe, expect, it } from 'vitest';
import {
  adminMemberPageSchema,
  auditLogPageSchema,
  roleChangeSchema,
} from './admin';

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
