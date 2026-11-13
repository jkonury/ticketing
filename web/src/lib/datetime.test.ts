import { expect, it } from 'vitest';
import { formatSeoul, seoulLocalToInstant } from './datetime';
it('한국 시각을 일관된 오전·오후 표기로 표시한다', () => {
  expect(formatSeoul('2026-11-20T08:00:00Z')).toBe('2026. 11. 20. 오후 5:00');
  expect(formatSeoul('2026-11-19T15:05:00Z')).toBe('2026. 11. 20. 오전 12:05');
  expect(formatSeoul('2026-11-20T03:09:00Z')).toBe('2026. 11. 20. 오후 12:09');
});
it('한국 시각을 UTC로 변환한다', () => {
  expect(seoulLocalToInstant('2026-12-01T19:30')).toBe(
    '2026-12-01T10:30:00.000Z',
  );
  expect(() => seoulLocalToInstant('2026-02-30T19:30')).toThrow();
});
