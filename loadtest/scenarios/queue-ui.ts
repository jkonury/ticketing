// 실행 중인 서비스의 지정 회차에 가상 대기자를 등록한다.
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';
import http from 'k6/http';
import { signAccessToken } from '../lib/jwt.ts';

const members = new SharedArray('queue-ui-members', () => JSON.parse(open(__ENV.MEMBERS_FILE)) as number[]);
const scheduleId = Number(__ENV.SCHEDULE_ID);
const vus = Number(__ENV.VUS || 3000);
const baseUrl = __ENV.BASE_URL || 'http://localhost:18000';

if (!Number.isInteger(scheduleId) || scheduleId < 1) throw new Error('SCHEDULE_ID가 필요합니다.');
if (!Number.isInteger(vus) || vus < 1 || vus > members.length) throw new Error('VUS와 회원 수를 확인하세요.');

export const options = {
  scenarios: {
    queue_ui: { executor: 'per-vu-iterations', vus, iterations: 1, maxDuration: '15m' },
  },
  thresholds: { checks: ['rate==1'] },
};

export default function () {
  const token = signAccessToken(members[exec.vu.idInTest - 1], 'USER');
  const params = { headers: { Authorization: `Bearer ${token}` }, tags: { name: 'queue_ui' } };
  const url = `${baseUrl}/api/v1/queues/${scheduleId}/entries`;
  const entered = http.post(url, null, params);
  if (!check(entered, { '대기열 등록 성공': (r) => r.status >= 200 && r.status < 300 })) return;
  let status = entered.json('status');
  while (status === 'WAITING') {
    sleep(5);
    const current = http.get(`${url}/me`, params);
    if (!check(current, { '대기 상태 조회 성공': (r) => r.status === 200 })) return;
    status = current.json('status');
  }
  check(status, { '입장 완료': (value) => value === 'ADMITTED' });
}
