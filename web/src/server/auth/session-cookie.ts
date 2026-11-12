import 'server-only';

import { cookies } from 'next/headers';
import { env } from '@/server/env';

export const SESSION_COOKIE = 'ticketing_session';
export async function readSessionId() {
  return (await cookies()).get(SESSION_COOKIE)?.value ?? null;
}
export async function writeSessionCookie(id: string) {
  (await cookies()).set(SESSION_COOKIE, id, {
    httpOnly: true,
    sameSite: 'lax',
    path: '/',
    secure: env.SESSION_COOKIE_SECURE,
    maxAge: 14 * 24 * 60 * 60,
  });
}
export async function clearSessionCookie() {
  (await cookies()).set(SESSION_COOKIE, '', {
    httpOnly: true,
    sameSite: 'lax',
    path: '/',
    secure: env.SESSION_COOKIE_SECURE,
    maxAge: 0,
  });
}
