import 'server-only';

import { z } from 'zod';
import { ApiError, problemSchema } from '@/lib/problem';
import { env } from '@/server/env';
import { sessions } from '@/server/auth/redis';
import { readSessionId } from '@/server/auth/session-cookie';
import type { SessionData, SessionStore } from '@/server/auth/session-store';

const tokenSchema = z.object({
  accessToken: z.string(),
  accessExpiresIn: z.number(),
  refreshToken: z.string(),
  refreshExpiresIn: z.number(),
});
export class SessionExpiredError extends Error {}
export const memberSchema = z.object({
  id: z.number(),
  email: z.string(),
  name: z.string(),
  role: z.enum(['USER', 'ORGANIZER']),
});
export type CallOptions<T> = {
  method?: string;
  path: string;
  body?: unknown;
  schema: z.ZodType<T> | null;
  auth: 'required' | 'optional' | 'none';
  timeoutMs?: number;
  headers?: { idempotencyKey?: string; admissionToken?: string };
  cache?: { revalidateSeconds: number };
};
type Dependencies = {
  fetch: typeof fetch;
  sessions: SessionStore;
  sessionId: () => Promise<string | null>;
  clock: () => number;
  baseUrl: string;
};

export function createGatewayClient(deps: Dependencies) {
  const flights = new Map<string, Promise<SessionData>>();
  async function request(path: string, init: RequestInit, timeoutMs = 3000) {
    try {
      return await deps.fetch(new URL(path, deps.baseUrl), {
        ...init,
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch (error) {
      if (
        typeof error === 'object' &&
        error !== null &&
        'name' in error &&
        (error.name === 'TimeoutError' || error.name === 'AbortError')
      )
        throw new ApiError(504, 'UPSTREAM_TIMEOUT');
      throw error;
    }
  }
  async function readError(response: Response): Promise<never> {
    const body: unknown = await response.json().catch(() => null);
    const parsed = problemSchema.safeParse(body);
    throw new ApiError(
      response.status,
      parsed.success ? parsed.data.code : undefined,
      undefined,
      parsed.success ? parsed.data.seatIds : undefined,
    );
  }
  async function refresh(id: string, old: SessionData): Promise<SessionData> {
    const existing = flights.get(id);
    if (existing) return existing;
    const work = (async () => {
      if (await deps.sessions.tryLock(id, 10000)) {
        try {
          const latest = await deps.sessions.get(id);
          if (!latest) throw new SessionExpiredError();
          if (
            latest.accessToken !== old.accessToken ||
            latest.refreshToken !== old.refreshToken
          )
            return latest;
          const response = await request('/api/v1/auth/tokens/refresh', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ refreshToken: latest.refreshToken }),
            cache: 'no-store',
          });
          if (response.status === 401) {
            await deps.sessions.destroy(id);
            throw new SessionExpiredError();
          }
          if (!response.ok) return await readError(response);
          const result = tokenSchema.safeParse(
            await response.json().catch(() => null),
          );
          if (!result.success) throw new ApiError(502, 'BAD_UPSTREAM_RESPONSE');
          const next = {
            ...latest,
            accessToken: result.data.accessToken,
            refreshToken: result.data.refreshToken,
            accessExpiresAt: deps.clock() + result.data.accessExpiresIn * 1000,
          };
          await deps.sessions.update(id, next);
          return next;
        } catch (error) {
          await deps.sessions.destroy(id);
          throw error;
        } finally {
          await deps.sessions.unlock(id);
        }
      }
      const deadline = Date.now() + 5000;
      while (Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, 50));
        const latest = await deps.sessions.get(id);
        if (!latest) throw new SessionExpiredError();
        if (
          latest.accessToken !== old.accessToken ||
          latest.refreshToken !== old.refreshToken
        )
          return latest;
      }
      throw new ApiError(504, 'UPSTREAM_TIMEOUT');
    })();
    flights.set(id, work);
    try {
      return await work;
    } finally {
      flights.delete(id);
    }
  }
  async function call<T>(opts: CallOptions<T>): Promise<T> {
    const method = opts.method ?? 'GET';
    if (opts.cache && (opts.auth !== 'none' || method !== 'GET'))
      throw new Error('공개 GET만 캐시할 수 있습니다');
    const id = opts.auth === 'none' ? null : await deps.sessionId();
    let session = id ? await deps.sessions.get(id) : null;
    if (!session && opts.auth === 'required') throw new SessionExpiredError();
    if (session && id && session.accessExpiresAt <= deps.clock() + 30000)
      session = await refresh(id, session);
    const send = (current: SessionData | null) => {
      const headers = new Headers();
      if (opts.body !== undefined)
        headers.set('Content-Type', 'application/json');
      if (current)
        headers.set('Authorization', `Bearer ${current.accessToken}`);
      if (opts.headers?.idempotencyKey)
        headers.set('Idempotency-Key', opts.headers.idempotencyKey);
      if (opts.headers?.admissionToken)
        headers.set('X-Admission-Token', opts.headers.admissionToken);
      return request(
        opts.path,
        {
          method,
          headers,
          body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
          ...(opts.cache
            ? { next: { revalidate: opts.cache.revalidateSeconds } }
            : { cache: 'no-store' }),
        },
        opts.timeoutMs,
      );
    };
    let response = await send(session);
    if (response.status === 401 && session && id) {
      session = await refresh(id, session);
      response = await send(session);
    }
    if (!response.ok) return await readError(response);
    // schema가 null이면 204 계약이므로 반환 값이 없다.
    if (!opts.schema) return undefined as T;
    const parsed = opts.schema.safeParse(
      await response.json().catch(() => null),
    );
    if (!parsed.success) throw new ApiError(502, 'BAD_UPSTREAM_RESPONSE');
    return parsed.data;
  }
  return { call };
}
export const gateway = createGatewayClient({
  fetch,
  sessions,
  sessionId: readSessionId,
  clock: Date.now,
  baseUrl: env.GATEWAY_URL,
});
export async function getMemberForLogin(token: string) {
  const response = await fetch(new URL('/api/v1/members/me', env.GATEWAY_URL), {
    headers: { Authorization: `Bearer ${token}` },
    cache: 'no-store',
    signal: AbortSignal.timeout(3000),
  });
  const result = memberSchema.safeParse(
    await response.json().catch(() => null),
  );
  if (!response.ok) throw new ApiError(response.status);
  if (!result.success) throw new ApiError(502, 'BAD_UPSTREAM_RESPONSE');
  return result.data;
}
