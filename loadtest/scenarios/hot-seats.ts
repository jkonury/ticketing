// 시나리오 B: 입장한 사용자가 같은 시각에 앞줄 100석을 놓고 경합한다(ADR 0024)
import { runBookingFlow } from '../lib/flow.ts';
import { hotSeatIds, scheduleId } from '../lib/data.ts';
import { systemTags, thresholdsFor, vus } from '../lib/options.ts';
import { compactSummary, type SummaryData } from '../lib/summary.ts';

const START_DELAY_MS = 60_000;
const HOT_SEAT_COUNT = 100;

const VUS = vus(1000);

export const options = {
  discardResponseBodies: true,
  systemTags,
  scenarios: {
    hot_seats: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '10m',
    },
  },
  thresholds: thresholdsFor(VUS),
};

// 모든 VU가 입장할 시간을 주고 같은 시각에 시작한다
export function setup() {
  return { startAt: Date.now() + START_DELAY_MS };
}

export default function (data: { startAt: number }) {
  runBookingFlow({
    scheduleId: scheduleId(),
    fixedCandidates: hotSeatIds(),
    conflictRetries: 0,
    startAt: data.startAt,
  });
}

// 확정 좌석이 핫 좌석 수를 넘으면 중복 판매다. 최종 판정은 verify-integrity.sh가 한다.
export function handleSummary(data: SummaryData) {
  const seats = data.metrics.seats_confirmed?.values?.count ?? 0;
  let text = compactSummary(data);
  if (seats > HOT_SEAT_COUNT) {
    text += `경고: 확정 좌석 ${seats}석이 핫 좌석 ${HOT_SEAT_COUNT}석을 넘었습니다. verify-integrity.sh로 중복 판매를 확인하세요.\n`;
  }
  return { stdout: text };
}
