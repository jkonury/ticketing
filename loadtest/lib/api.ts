// Gateway 호출 래퍼. 요청마다 고정 이름의 name 태그를 붙여 URL에 ID가 들어가도 지표 cardinality가 늘지 않게 한다.
import http from 'k6/http';
import { sleep } from 'k6';
import { namedHttpDuration, unexpectedErrors, unexpectedResponses } from './metrics.ts';

const BASE_URL = __ENV.BASE_URL;
const MAX_RATE_LIMIT_RETRIES = 5;
// LOADTEST_DEBUG=1이면 예상하지 못한 응답을 k6 로그에 남긴다(원인 분석용)
const DEBUG = __ENV.LOADTEST_DEBUG === '1';

// 409·429는 예상 응답이므로 http_req_failed에 넣지 않는다
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 429));

interface RequestOptions {
  body?: unknown;
  headers?: Record<string, string>;
  // 본문이 필요한 요청에만 켠다(기본은 discardResponseBodies로 버린다)
  readBody?: boolean;
}

function isExpected(name: string, status: number, body: string | null): boolean {
  if (status >= 200 && status < 300) return true;
  if (status === 429) return true;
  return name === 'booking_create' && status === 409 && body !== null && body.includes('SEAT_ALREADY_HELD');
}

function retryDelaySeconds(retryAfter: string | undefined): number {
  const seconds = Number(retryAfter);
  return Number.isFinite(seconds) && seconds > 0 ? Math.min(seconds, 5) : 1;
}

function request(name: string, method: string, path: string, token: string, options: RequestOptions = {}) {
  const headers: Record<string, string> = { Authorization: `Bearer ${token}`, ...options.headers };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  const params: Record<string, unknown> = { headers, tags: { name } };
  if (options.readBody) params.responseType = 'text';
  const body = options.body === undefined ? null : JSON.stringify(options.body);

  for (let attempt = 0; ; attempt++) {
    const response = http.request(method, `${BASE_URL}${path}`, body, params);
    const trend = namedHttpDuration[name as keyof typeof namedHttpDuration];
    if (trend !== undefined) trend.add(response.timings.duration);
    const responseBody = typeof response.body === 'string' ? response.body : null;
    const expected = isExpected(name, response.status, responseBody);
    unexpectedErrors.add(!expected);
    if (!expected) {
      unexpectedResponses.add(1, { name, status: String(response.status) });
      if (DEBUG) console.error(`예상하지 못한 응답: ${name} ${response.status} ${responseBody ?? ''}`);
    }
    if (response.status === 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
      sleep(retryDelaySeconds(response.headers['Retry-After']));
      continue;
    }
    return response;
  }
}

export function ok(status: number): boolean {
  return status >= 200 && status < 300;
}

export function enterQueue(token: string, scheduleId: number) {
  return request('queue_enter', 'POST', `/api/v1/queues/${scheduleId}/entries`, token);
}

// SSE 티켓은 1회용이라 스트림을 열 때마다 새로 받는다
export function issueSseTicket(token: string): string | null {
  const response = request('sse_ticket', 'POST', '/api/v1/sse-tickets', token, { readBody: true });
  if (!ok(response.status)) return null;
  return (response.json() as { ticket: string }).ticket;
}

export function createBooking(
  token: string,
  admissionToken: string,
  idempotencyKey: string,
  scheduleId: number,
  seatIds: number[],
) {
  return request('booking_create', 'POST', '/api/v1/bookings', token, {
    body: { scheduleId, seatIds },
    headers: { 'X-Admission-Token': admissionToken, 'Idempotency-Key': idempotencyKey },
    readBody: true,
  });
}

export function payBooking(token: string, bookingId: number, idempotencyKey: string) {
  return request('booking_pay', 'POST', `/api/v1/bookings/${bookingId}/payments`, token, {
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}

export function bookingDetail(token: string, bookingId: number) {
  return request('booking_detail', 'GET', `/api/v1/bookings/${bookingId}`, token, { readBody: true });
}
