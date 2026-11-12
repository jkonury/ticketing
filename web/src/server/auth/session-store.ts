import 'server-only';

import { randomBytes } from 'node:crypto';
import { z } from 'zod';

const sessionSchema = z.object({
  memberId: z.number(),
  name: z.string(),
  role: z.enum(['USER', 'ORGANIZER']),
  accessToken: z.string(),
  accessExpiresAt: z.number(),
  refreshToken: z.string(),
});
export type SessionData = z.infer<typeof sessionSchema>;
export interface KeyValueStore {
  get(key: string): Promise<string | null>;
  set(
    key: string,
    value: string,
    opts: { pxMs: number; nx?: boolean },
  ): Promise<boolean>;
  del(key: string): Promise<void>;
}
const ttl = 14 * 24 * 60 * 60 * 1000;
const sessionKey = (id: string) => `bff:session:${id}`;
const lockKey = (id: string) => `bff:refresh-lock:${id}`;

export function createSessionStore(kv: KeyValueStore, clock: () => number) {
  return {
    async create(data: SessionData) {
      const id = randomBytes(32).toString('base64url');
      await kv.set(sessionKey(id), JSON.stringify(data), { pxMs: ttl });
      return id;
    },
    async get(id: string) {
      const raw = await kv.get(sessionKey(id));
      if (!raw) return null;
      try {
        const result = sessionSchema.safeParse(JSON.parse(raw));
        return result.success ? result.data : null;
      } catch {
        return null;
      }
    },
    async update(id: string, data: SessionData) {
      await kv.set(sessionKey(id), JSON.stringify(data), { pxMs: ttl });
    },
    async destroy(id: string) {
      await kv.del(sessionKey(id));
    },
    async tryLock(id: string, ttlMs: number) {
      return kv.set(lockKey(id), String(clock()), { pxMs: ttlMs, nx: true });
    },
    async unlock(id: string) {
      await kv.del(lockKey(id));
    },
  };
}
export type SessionStore = ReturnType<typeof createSessionStore>;
