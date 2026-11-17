import 'server-only';

import { z } from 'zod';
import {
  gateway,
  getMemberForLogin,
  memberSchema,
} from '@/server/api/gateway-client';
import { sessions } from './redis';
import {
  clearSessionCookie,
  readSessionId,
  writeSessionCookie,
} from './session-cookie';

const tokensSchema = z.object({
  accessToken: z.string(),
  accessExpiresIn: z.number(),
  refreshToken: z.string(),
  refreshExpiresIn: z.number(),
});
type SignupInput = {
  email: string;
  password: string;
  name: string;
  role: 'USER' | 'ORGANIZER' | 'ADMIN';
};
export async function signup(input: SignupInput) {
  await gateway.call({
    path: '/api/v1/members',
    method: 'POST',
    body: input,
    schema: memberSchema,
    auth: 'none',
  });
}
export async function login(input: { email: string; password: string }) {
  const tokens = await gateway.call({
    path: '/api/v1/auth/tokens',
    method: 'POST',
    body: input,
    schema: tokensSchema,
    auth: 'none',
  });
  const member = await getMemberForLogin(tokens.accessToken);
  const id = await sessions.create({
    memberId: member.id,
    name: member.name,
    role: member.role,
    accessToken: tokens.accessToken,
    accessExpiresAt: Date.now() + tokens.accessExpiresIn * 1000,
    refreshToken: tokens.refreshToken,
  });
  await writeSessionCookie(id);
}
type LogoutDeps = {
  sessionId: typeof readSessionId;
  get: typeof sessions.get;
  destroy: typeof sessions.destroy;
  clear: typeof clearSessionCookie;
  revoke: (token: string) => Promise<void>;
};
export function createLogout(deps: LogoutDeps) {
  return async function logout() {
    const id = await deps.sessionId();
    try {
      const session = id ? await deps.get(id) : null;
      if (session) await deps.revoke(session.refreshToken);
    } catch {
      /* 폐기에 실패해도 로컬 세션은 종료한다. */
    } finally {
      try {
        if (id) await deps.destroy(id);
      } finally {
        await deps.clear();
      }
    }
  };
}
export const logout = createLogout({
  sessionId: readSessionId,
  get: sessions.get,
  destroy: sessions.destroy,
  clear: clearSessionCookie,
  async revoke(token) {
    await gateway.call({
      path: '/api/v1/auth/tokens/revoke',
      method: 'POST',
      body: { refreshToken: token },
      schema: null,
      auth: 'none',
    });
  },
});
