import { expect, it } from 'vitest';
import { seoulLocalToInstant } from './datetime';
it('한국 시각을 UTC로 변환한다', () => {
  expect(seoulLocalToInstant('2026-12-01T19:30')).toBe(
    '2026-12-01T10:30:00.000Z',
  );
  expect(() => seoulLocalToInstant('2026-02-30T19:30')).toThrow();
});
