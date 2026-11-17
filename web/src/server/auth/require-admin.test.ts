import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ForbiddenError, requireAdmin } from './require-admin';
import { requireUser } from './require-user';

vi.mock('./require-user', () => ({ requireUser: vi.fn() }));

beforeEach(() => vi.resetAllMocks());

describe('requireAdmin', () => {
  it('비로그인은 requireUser의 redirect를 그대로 전파한다', async () => {
    vi.mocked(requireUser).mockRejectedValue(new Error('NEXT_REDIRECT'));
    await expect(requireAdmin()).rejects.toThrow('NEXT_REDIRECT');
  });
  it('관리자가 아니면 ForbiddenError를 던진다', async () => {
    vi.mocked(requireUser).mockResolvedValue({
      memberId: 1,
      name: 'a',
      role: 'ORGANIZER',
    });
    await expect(requireAdmin()).rejects.toBeInstanceOf(ForbiddenError);
  });
  it('관리자는 사용자를 반환한다', async () => {
    vi.mocked(requireUser).mockResolvedValue({
      memberId: 1,
      name: 'a',
      role: 'ADMIN',
    });
    await expect(requireAdmin()).resolves.toMatchObject({ role: 'ADMIN' });
  });
});
