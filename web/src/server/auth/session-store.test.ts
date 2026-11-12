import { describe, expect, it, vi } from 'vitest';
import { createSessionStore, type KeyValueStore } from './session-store';

describe('세션 저장소', () => {
  it('생성, 조회, 삭제와 TTL을 처리한다', async () => {
    const values = new Map<string, string>();
    const set = vi.fn(async (key: string, value: string) => {
      values.set(key, value);
      return true;
    });
    const kv: KeyValueStore = {
      get: async (key) => values.get(key) ?? null,
      set,
      del: async (key) => {
        values.delete(key);
      },
    };
    const store = createSessionStore(kv, () => 0);
    const data = {
      memberId: 1,
      name: '이름',
      role: 'USER' as const,
      accessToken: 'a',
      accessExpiresAt: 1,
      refreshToken: 'r',
    };
    const id = await store.create(data);
    expect(await store.get(id)).toEqual(data);
    expect(set).toHaveBeenCalledWith(
      `bff:session:${id}`,
      JSON.stringify(data),
      { pxMs: 1209600000 },
    );
    await store.destroy(id);
    expect(await store.get(id)).toBeNull();
    values.set('bff:session:bad', '{');
    expect(await store.get('bad')).toBeNull();
  });
});
