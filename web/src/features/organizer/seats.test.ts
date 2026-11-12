import { describe, expect, it } from 'vitest';
import { makeSeats } from './seats';
describe('좌석 생성', () => {
  it('구역마다 앞 열부터 등급을 배정한다', () => {
    const seats = makeSeats(['A', 'B'], 3, 2, { VIP: 1, R: 1, S: 0, A: 1 });
    expect(seats).toHaveLength(12);
    expect(
      seats
        .filter((seat) => seat.section === 'B' && seat.number === 1)
        .map((seat) => seat.grade),
    ).toEqual(['VIP', 'R', 'A']);
  });
  it('등급별 열 수의 합이 다르면 거부한다', () => {
    expect(() => makeSeats(['A'], 2, 1, { VIP: 1, R: 0, S: 0, A: 0 })).toThrow(
      'ROW_COUNT_MISMATCH',
    );
  });
  it('5000석을 넘으면 거부한다', () => {
    expect(() =>
      makeSeats(['A'], 51, 100, { VIP: 51, R: 0, S: 0, A: 0 }),
    ).toThrow('TOO_MANY_SEATS');
  });
});
