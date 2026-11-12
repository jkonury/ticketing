import { describe, expect, it } from 'vitest';
import { assertSameOrigin } from './csrf';

describe('CSRF 검사', () => {
  it('같은 origin과 Referer 대체를 허용한다', () => {
    expect(() =>
      assertSameOrigin(new Headers({ origin: 'http://localhost:3000' })),
    ).not.toThrow();
    expect(() =>
      assertSameOrigin(new Headers({ referer: 'http://localhost:3000/path' })),
    ).not.toThrow();
  });
  it('다른 origin과 누락을 거부한다', () => {
    expect(() =>
      assertSameOrigin(
        new Headers({
          origin: 'http://other.test',
          referer: 'http://localhost:3000/',
        }),
      ),
    ).toThrow();
    expect(() => assertSameOrigin(new Headers())).toThrow();
  });
});
