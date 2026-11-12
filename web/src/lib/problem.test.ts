import { describe, expect, it } from 'vitest';
import { problemSchema } from './problem';

describe('ProblemDetail', () => {
  it('코드가 있는 응답을 파싱한다', () => {
    expect(
      problemSchema.parse({ status: 400, code: 'INVALID_REQUEST' }),
    ).toEqual({
      status: 400,
      code: 'INVALID_REQUEST',
    });
  });

  it('코드가 없는 응답을 파싱한다', () => {
    expect(problemSchema.parse({ status: 500 })).toEqual({ status: 500 });
  });
});
