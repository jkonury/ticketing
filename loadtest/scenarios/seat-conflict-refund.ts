// 좌석 충돌 환불 보상 검증(ADR 0024 확장). 선점 키 유실을 인위적으로 주입한 정합성 검증이며 정상 A·B 성능 비교와 별도로 해석한다.
// 두 단계로 실행한다. runner가 첫 단계 뒤 첫 예매의 겹치는 좌석 선점 키만 지운다.
//   PHASE=first : 그룹마다 첫 회원(A)이 대기열 → 입장 → 좌석 A로 PENDING 예매를 만든다(100 VU).
//   PHASE=second: 둘째 회원(B)이 대기열 → 입장 → 겹치는 좌석으로 예매하고, 같은 시각에 A·B 모두 결제를 요청한다(200 VU).
// 결제 요청은 같은 멱등 키로 한 번 더 보내 추가 확정·환불이 생기지 않는지 확인한다. 어느 쪽이 확정될지는 고정하지 않는다.
import { sleep } from 'k6';
import exec from 'k6/execution';
import { bookingDetail, createBooking, enterQueue, ok, payBooking } from '../lib/api.ts';
import { conflict, firstBookingOf, pairAt, PAIR_COUNT } from '../lib/conflict.ts';
import { memberIdForVu, scheduleId } from '../lib/data.ts';
import { signAccessToken } from '../lib/jwt.ts';
import { flowStage } from '../lib/metrics.ts';
import { systemTags, thresholdsFor } from '../lib/options.ts';
import { waitForAdmission } from '../lib/sse.ts';
import { compactSummary, readinessReport, type SummaryData } from '../lib/summary.ts';

const PHASE = __ENV.PHASE;
if (PHASE !== 'first' && PHASE !== 'second') throw new Error('PHASE는 first 또는 second여야 합니다.');
const SECOND_START_DELAY_MS = 45_000;
const POLL_ATTEMPTS = 30;
const VUS = PHASE === 'first' ? PAIR_COUNT : PAIR_COUNT * 2;

export const options = {
  discardResponseBodies: true,
  systemTags,
  scenarios: {
    seat_conflict_refund: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '10m' },
  },
  thresholds: thresholdsFor(VUS),
};

export function setup() {
  return { startAt: Date.now() + SECOND_START_DELAY_MS };
}

function idempotencyKey(): string {
  return `k6c-${exec.vu.idInTest}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function enterAndAdmit(token: string, scheduleIdValue: number) {
  if (!ok(enterQueue(token, scheduleIdValue).status)) return null;
  flowStage.registered.add(1);
  const admission = waitForAdmission(scheduleIdValue, token);
  if (admission !== null) flowStage.admitted.add(1);
  return admission;
}

function runFirst(): void {
  const pair = exec.vu.idInTest;
  const memberId = memberIdForVu();
  const token = signAccessToken(memberId, 'USER');
  const admission = enterAndAdmit(token, scheduleId());
  if (admission === null) return;
  flowStage.booking_attempt.add(1);
  const response = createBooking(token, admission.admissionToken, idempotencyKey(), scheduleId(), pairAt(pair).seatsA);
  if (!ok(response.status)) return;
  const { bookingId } = response.json() as { bookingId: number };
  flowStage.booking_created.add(1);
  console.log(`PAIR_FIRST ${JSON.stringify({ pair, memberId, bookingId })}`);
}

function runSecond(data: { startAt: number }): void {
  const vu = exec.vu.idInTest;
  const role = vu <= PAIR_COUNT ? 'A' : 'B';
  const pair = role === 'A' ? vu : vu - PAIR_COUNT;
  const memberId = memberIdForVu();
  const token = signAccessToken(memberId, 'USER');
  let bookingId: number;
  let createdOnTime = true;
  if (role === 'A') {
    bookingId = firstBookingOf(pair).bookingId;
  } else {
    for (const stage of [flowStage.registered, flowStage.admitted, flowStage.ready_on_time, flowStage.ready_late, flowStage.booking_attempt]) stage.add(0);
    const admission = enterAndAdmit(token, scheduleId());
    if (admission === null) return;
    (Date.now() <= data.startAt ? flowStage.ready_on_time : flowStage.ready_late).add(1);
    flowStage.booking_attempt.add(1);
    const response = createBooking(token, admission.admissionToken, idempotencyKey(), scheduleId(), pairAt(pair).seatsB);
    if (!ok(response.status)) return;
    bookingId = (response.json() as { bookingId: number }).bookingId;
    flowStage.booking_created.add(1);
    createdOnTime = Date.now() <= data.startAt;
  }
  const remaining = data.startAt - Date.now();
  if (remaining > 0) sleep(remaining / 1000);

  const payKey = idempotencyKey();
  const paid = payBooking(token, bookingId, payKey);
  if (!ok(paid.status)) {
    console.log(`PAIR_SECOND ${JSON.stringify({ pair, role, memberId, bookingId, payStatus: paid.status })}`);
    return;
  }
  conflict.payAccepted.add(1);
  flowStage.payment_requested.add(1);
  const repaid = payBooking(token, bookingId, payKey);
  (ok(repaid.status) ? conflict.repayAccepted : conflict.repayRejected).add(1);

  let status = 'PENDING';
  for (let attempt = 0; attempt < POLL_ATTEMPTS; attempt++) {
    sleep(1);
    const detail = bookingDetail(token, bookingId);
    if (!ok(detail.status)) continue;
    status = (detail.json() as { status: string }).status;
    if (status !== 'PENDING') break;
  }
  (status === 'CONFIRMED' ? conflict.finalConfirmed : status === 'CANCELLED' ? conflict.finalCancelled : conflict.finalOther).add(1);
  console.log(
    `PAIR_SECOND ${JSON.stringify({ pair, role, memberId, bookingId, createdOnTime, payStatus: paid.status, repayStatus: repaid.status, finalStatus: status })}`,
  );
}

export default function (data: { startAt: number }) {
  if (PHASE === 'first') runFirst();
  else runSecond(data);
}

export function handleSummary(data: SummaryData & { setup_data?: { startAt?: number } }) {
  const output: Record<string, string> = { stdout: compactSummary(data) };
  if (__ENV.READINESS_FILE) {
    const report = { phase: PHASE, pairs: PAIR_COUNT, ...readinessReport(data, PAIR_COUNT, data.setup_data?.startAt) };
    output[__ENV.READINESS_FILE] = JSON.stringify(report, null, 2);
  }
  return output;
}
