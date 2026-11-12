import 'server-only';

import Redis from 'ioredis';
import { env } from '@/server/env';
import { createSessionStore, type KeyValueStore } from './session-store';

const redis = new Redis(env.REDIS_URL, { lazyConnect: true });
const kv: KeyValueStore = {
  get: (key) => redis.get(key),
  async set(key, value, opts) {
    const result = opts.nx
      ? await redis.set(key, value, 'PX', opts.pxMs, 'NX')
      : await redis.set(key, value, 'PX', opts.pxMs);
    return result === 'OK';
  },
  async del(key) {
    await redis.del(key);
  },
};
export const sessions = createSessionStore(kv, Date.now);
