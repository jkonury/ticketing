import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

interface Members {
  userIdMin: number;
  userIdMax: number;
}

const members = new SharedArray('members', () => [JSON.parse(open(__ENV.MEMBERS_FILE)) as Members]);
const schedule = new SharedArray('schedule', () => {
  const { scheduleId } = JSON.parse(open(__ENV.SCHEDULE_FILE));
  return [scheduleId];
});
// 요소 단위로 공유해 VU마다 좌석 목록 전체를 복사하지 않는다(100석뿐이라 VU 안에서 한 번만 읽는다).
const hotSeats = new SharedArray('hotSeatIds', () => JSON.parse(open(__ENV.SCHEDULE_FILE)).hotSeatIds as number[]);

export function scheduleId(): number {
  return schedule[0];
}

export function hotSeatIds(): number[] {
  return Array.from({ length: hotSeats.length }, (_, index) => hotSeats[index]);
}

// VU마다 다른 회원을 쓴다. 회원 한 명을 공유하면 회원별 rate limit과 대기열 항목이 겹친다.
export function memberIdForVu(): number {
  const { userIdMin, userIdMax } = members[0];
  const memberId = userIdMin + exec.vu.idInTest - 1;
  if (memberId > userIdMax) {
    throw new Error(`VU ${exec.vu.idInTest}에 배정할 회원이 없습니다(회원 ID ${userIdMin}~${userIdMax}).`);
  }
  return memberId;
}
