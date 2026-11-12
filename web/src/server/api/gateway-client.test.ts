import { describe, expect, it, vi } from 'vitest';
import { z } from 'zod';
import { ApiError } from '@/lib/problem';
import {
  createSessionStore,
  type KeyValueStore,
} from '@/server/auth/session-store';
import { createGatewayClient, SessionExpiredError } from './gateway-client';

function setup(expiresAt = 0) {
  const map = new Map<string, string>();
  const kv: KeyValueStore = {
    get: async (key) => map.get(key) ?? null,
    set: async (key, value, opts) => {
      if (opts.nx && map.has(key)) return false;
      map.set(key, value);
      return true;
    },
    del: async (key) => {
      map.delete(key);
    },
  };
  const sessions = createSessionStore(kv, () => 100000);
  const data = {
    memberId: 1,
    name: '이름',
    role: 'USER' as const,
    accessToken: 'old',
    accessExpiresAt: expiresAt,
    refreshToken: 'refresh',
  };
  const json = (value: unknown, status = 200) =>
    new Response(JSON.stringify(value), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  const tokens = {
    accessToken: 'new',
    refreshToken: 'new-refresh',
    accessExpiresIn: 900,
    refreshExpiresIn: 1209600,
  };
  return { map, sessions, data, json, tokens };
}
describe('Gateway 클라이언트', () => {
  it('동시 만료 요청은 한 번만 갱신한다', async () => {
    const t = setup();
    const id = await t.sessions.create(t.data);
    const fetcher = vi.fn<typeof fetch>(async (url) =>
      String(url).endsWith('/refresh')
        ? t.json(t.tokens)
        : t.json({ ok: true }),
    );
    const client = createGatewayClient({
      fetch: fetcher,
      sessions: t.sessions,
      sessionId: async () => id,
      clock: () => 100000,
      baseUrl: 'http://example.test',
    });
    await Promise.all(
      Array.from({ length: 5 }, () =>
        client.call({
          path: '/test',
          schema: z.object({ ok: z.boolean() }),
          auth: 'required',
        }),
      ),
    );
    expect(
      fetcher.mock.calls.filter(([url]) => String(url).endsWith('/refresh')),
    ).toHaveLength(1);
  });
  it('401이면 한 번 갱신하고 한 번 재시도하며 두 번째 401은 오류다', async () => {
    const t = setup(999999);
    const id = await t.sessions.create(t.data);
    const fetcher = vi.fn<typeof fetch>(async (url) =>
      String(url).endsWith('/refresh')
        ? t.json(t.tokens)
        : t.json({ status: 401, code: 'UNAUTHORIZED' }, 401),
    );
    const client = createGatewayClient({
      fetch: fetcher,
      sessions: t.sessions,
      sessionId: async () => id,
      clock: () => 100000,
      baseUrl: 'http://example.test',
    });
    await expect(
      client.call({ path: '/test', schema: z.object({}), auth: 'required' }),
    ).rejects.toMatchObject({ status: 401, code: 'UNAUTHORIZED' });
    expect(fetcher).toHaveBeenCalledTimes(3);
  });
  it('갱신 401이면 세션을 파기한다', async () => {
    const t = setup();
    const id = await t.sessions.create(t.data);
    const client = createGatewayClient({
      fetch: vi.fn(async () => t.json({ status: 401 }, 401)),
      sessions: t.sessions,
      sessionId: async () => id,
      clock: () => 100000,
      baseUrl: 'http://example.test',
    });
    await expect(
      client.call({ path: '/test', schema: z.object({}), auth: 'required' }),
    ).rejects.toBeInstanceOf(SessionExpiredError);
    expect(await t.sessions.get(id)).toBeNull();
  });
  it('다른 인스턴스가 갱신한 토큰을 사용한다', async () => {
    const t = setup();
    const id = await t.sessions.create(t.data);
    await t.sessions.tryLock(id, 10000);
    const fetcher = vi.fn<typeof fetch>(async () => t.json({ ok: true }));
    const client = createGatewayClient({
      fetch: fetcher,
      sessions: t.sessions,
      sessionId: async () => id,
      clock: () => 100000,
      baseUrl: 'http://example.test',
    });
    const pending = client.call({
      path: '/test',
      schema: z.object({ ok: z.boolean() }),
      auth: 'required',
    });
    setTimeout(() => {
      void t.sessions.update(id, {
        ...t.data,
        accessToken: 'other',
        refreshToken: 'other-refresh',
      });
    }, 10);
    await pending;
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(
      new Headers(fetcher.mock.calls[0][1]?.headers).get('Authorization'),
    ).toBe('Bearer other');
  });
  it('시간 초과와 잘못된 응답을 변환한다', async () => {
    const t = setup();
    const deps = {
      sessions: t.sessions,
      sessionId: async () => null,
      clock: () => 100000,
      baseUrl: 'http://example.test',
    };
    const timeout = createGatewayClient({
      ...deps,
      fetch: vi.fn(async () => {
        throw new DOMException('timeout', 'TimeoutError');
      }),
    });
    await expect(
      timeout.call({ path: '/test', schema: z.object({}), auth: 'none' }),
    ).rejects.toMatchObject({ status: 504, code: 'UPSTREAM_TIMEOUT' });
    const bad = createGatewayClient({
      ...deps,
      fetch: vi.fn(async () => t.json({ invalid: true })),
    });
    await expect(
      bad.call({
        path: '/test',
        schema: z.object({ ok: z.boolean() }),
        auth: 'none',
      }),
    ).rejects.toEqual(new ApiError(502, 'BAD_UPSTREAM_RESPONSE'));
  });
});
