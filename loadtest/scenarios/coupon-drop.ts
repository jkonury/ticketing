// 시나리오 C: 많은 회원이 같은 시각에 같은 선착순(LIMITED) 쿠폰을 받는다(M5.5-3)
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';
import { signAccessToken } from '../lib/jwt.ts';
import { systemTags, vus } from '../lib/options.ts';
import { compactSummary, type SummaryData } from '../lib/summary.ts';

const START_DELAY_MS = 30_000;
const VUS = vus(10000);

// lib/data.ts는 회차 파일을 함께 열어 쓰지 않는다. 회원 범위만 읽는다
const members = new SharedArray('couponMembers', () => [JSON.parse(open(__ENV.MEMBERS_FILE)) as { userIdMin: number; userIdMax: number }]);

function memberIdForVu(): number {
  const memberId = members[0].userIdMin + exec.vu.idInTest - 1;
  if (memberId > members[0].userIdMax) {
    throw new Error(`VU ${exec.vu.idInTest}에 배정할 회원이 없습니다(회원 ID ${members[0].userIdMin}~${members[0].userIdMax}).`);
  }
  return memberId;
}

const definition = new SharedArray('couponDefinition', () => {
  const { definitionId, totalQuantity } = JSON.parse(open(__ENV.COUPON_FILE));
  return [{ definitionId, totalQuantity }];
});

// 결과 분류. 기타(claim_other)는 예상하지 못한 응답·네트워크 오류이며 0이어야 한다
const created = new Counter('claim_created');
const alreadyClaimed = new Counter('claim_already_claimed');
const soldOut = new Counter('claim_sold_out');
const rateLimited = new Counter('claim_rate_limited');
const other = new Counter('claim_other');
const claimLatency = new Trend('claim_latency', true);

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 429));

export const options = {
  discardResponseBodies: true,
  systemTags,
  scenarios: {
    coupon_drop: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '10m',
    },
  },
  thresholds: {
    claim_other: ['count<1'],
    claim_latency: ['p(95)<1000'],
  },
};

export function setup() {
  return { startAt: Date.now() + START_DELAY_MS };
}

export default function (data: { startAt: number }) {
  for (const counter of [created, alreadyClaimed, soldOut, rateLimited, other]) counter.add(0);
  const token = signAccessToken(memberIdForVu(), 'USER');
  const remaining = data.startAt - Date.now();
  if (remaining > 0) sleep(remaining / 1000);

  const response = http.post(`${__ENV.BASE_URL}/api/v1/coupons/${definition[0].definitionId}/claims`, null, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'coupon_claim' },
    responseType: 'text',
  });
  claimLatency.add(response.timings.duration);
  const body = typeof response.body === 'string' ? response.body : '';
  if (response.status >= 200 && response.status < 300) created.add(1);
  else if (response.status === 409 && body.includes('COUPON_ALREADY_CLAIMED')) alreadyClaimed.add(1);
  else if (response.status === 409 && body.includes('COUPON_SOLD_OUT')) soldOut.add(1);
  else if (response.status === 429) rateLimited.add(1);
  else {
    other.add(1);
    if (__ENV.LOADTEST_DEBUG === '1') console.error(`예상하지 못한 응답: ${response.status} ${body}`);
  }
}

// 발급(201)이 총수량을 넘으면 초과 발급이다. 최종 판정은 verify-coupon-integrity.sh가 한다.
export function handleSummary(data: SummaryData) {
  const issued = data.metrics.claim_created?.values?.count ?? 0;
  const total = definition[0].totalQuantity;
  let text = compactSummary(data);
  if (issued > total) text += `경고: 발급 응답 ${issued}건이 총수량 ${total}장을 넘었습니다.\n`;
  return { stdout: text };
}
