// 커스텀 k6 빌드(xk6-sse)와 Gateway 연결을 확인하는 최소 시나리오
import http from 'k6/http';
import { check } from 'k6';
import sse from 'k6/x/sse';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1'],
  },
};

export default function () {
  check(sse, { 'xk6-sse 모듈 로드': (module) => module !== undefined });
  const response = http.get(`${__ENV.BASE_URL}/api/v1/performances`);
  check(response, { 'GET /api/v1/performances 200': (r) => r.status === 200 });
}
