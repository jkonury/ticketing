import { expect, it, vi } from 'vitest';
import { createLogout } from './auth-service';

it('토큰 폐기 실패에도 세션과 쿠키를 삭제한다', async () => {
  const destroy = vi.fn(async () => {});
  const clear = vi.fn(async () => {});
  const logout = createLogout({
    sessionId: async () => 'id',
    get: async () => ({
      memberId: 1,
      name: '이름',
      role: 'USER',
      accessToken: 'a',
      accessExpiresAt: 1,
      refreshToken: 'r',
    }),
    destroy,
    clear,
    revoke: async () => {
      throw new Error('failed');
    },
  });
  await logout();
  expect(destroy).toHaveBeenCalledWith('id');
  expect(clear).toHaveBeenCalledOnce();
});
