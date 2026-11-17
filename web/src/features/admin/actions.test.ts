import { beforeEach, describe, expect, it, vi } from 'vitest';
import { headers } from 'next/headers';
import { changeMemberRole } from '@/server/api/admin';
import { ForbiddenError, requireAdmin } from '@/server/auth/require-admin';
import { changeRoleAction } from './actions';

vi.mock('next/headers', () => ({ headers: vi.fn() }));
vi.mock('next/cache', () => ({ revalidatePath: vi.fn() }));
vi.mock('@/server/api/admin', async (original) => ({
  ...(await original<typeof import('@/server/api/admin')>()),
  changeMemberRole: vi.fn(),
}));
vi.mock('@/server/auth/require-admin', async (original) => ({
  ...(await original<typeof import('@/server/auth/require-admin')>()),
  requireAdmin: vi.fn(),
}));
vi.mock('@/server/auth/require-user', () => ({
  withSessionRedirect: <T>(work: () => Promise<T>) => work(),
}));

function form() {
  const data = new FormData();
  data.set('memberId', '3');
  data.set('role', 'ORGANIZER');
  return data;
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(headers).mockResolvedValue(
    new Headers({ origin: 'http://localhost:13000' }) as never,
  );
});

describe('역할 변경 액션', () => {
  it('Origin이 다르면 거부한다', async () => {
    vi.mocked(headers).mockResolvedValue(
      new Headers({ origin: 'http://evil.test' }) as never,
    );
    await expect(changeRoleAction({ message: '' }, form())).rejects.toThrow();
    expect(changeMemberRole).not.toHaveBeenCalled();
  });
  it('관리자가 아니면 거부한다', async () => {
    vi.mocked(requireAdmin).mockRejectedValue(new ForbiddenError());
    const result = await changeRoleAction({ message: '' }, form());
    expect(result.message).toContain('FORBIDDEN');
    expect(changeMemberRole).not.toHaveBeenCalled();
  });
  it('관리자는 역할을 변경한다', async () => {
    vi.mocked(requireAdmin).mockResolvedValue({
      memberId: 1,
      name: 'a',
      role: 'ADMIN',
    });
    vi.mocked(changeMemberRole).mockResolvedValue({
      id: 3,
      email: 'x@y.z',
      name: 'x',
      role: 'ORGANIZER',
    });
    const result = await changeRoleAction({ message: '' }, form());
    expect(changeMemberRole).toHaveBeenCalledWith(3, 'ORGANIZER');
    expect(result.message).toContain('ORGANIZER');
  });
});
