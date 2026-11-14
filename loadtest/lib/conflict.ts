// seat-conflict-refund 시나리오 전용 데이터와 지표. 정상 A·B가 쓰는 공용 지표(unexpected_errors 등)의 정의는 바꾸지 않는다.
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

export const PAIR_COUNT = 100;

export interface ConflictPair {
  pair: number;
  seatsA: number[];
  seatsB: number[];
  overlapSeatId: number;
}

export interface FirstBooking {
  pair: number;
  memberId: number;
  bookingId: number;
}

const pairs = new SharedArray('conflictPairs', () => JSON.parse(open(__ENV.SCHEDULE_FILE)).conflictPairs as ConflictPair[]);
// 둘째 단계에서만 쓴다. runner가 첫 단계 로그에서 만든 파일이다
const firstBookings = new SharedArray('conflictFirstBookings', () => (__ENV.FIRST_FILE ? (JSON.parse(open(__ENV.FIRST_FILE)) as FirstBooking[]) : []));

export function pairAt(pair: number): ConflictPair {
  const found = pairs[pair - 1];
  if (found === undefined || found.pair !== pair) throw new Error(`좌석 그룹 ${pair}이 시드에 없습니다(conflictPairs ${pairs.length}개).`);
  return found;
}

export function firstBookingOf(pair: number): FirstBooking {
  const found = firstBookings.find((booking) => booking.pair === pair);
  if (found === undefined) throw new Error(`그룹 ${pair}의 첫 예매가 FIRST_FILE에 없습니다.`);
  return found;
}

export const conflict = {
  payAccepted: new Counter('conflict_pay_accepted'),
  repayAccepted: new Counter('conflict_repay_accepted'),
  repayRejected: new Counter('conflict_repay_rejected'),
  finalConfirmed: new Counter('conflict_final_confirmed'),
  finalCancelled: new Counter('conflict_final_cancelled'),
  finalOther: new Counter('conflict_final_other'),
};
