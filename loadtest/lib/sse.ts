// xk6-sse 기반 스트림 헬퍼. 스트림을 열 때마다 SSE 티켓을 새로 발급한다.
import sse from 'k6/x/sse';
import { issueSseTicket } from './api.ts';
import { sseConnectSuccess, sseFailures, unexpectedErrors } from './metrics.ts';

const BASE_URL = __ENV.BASE_URL;
const QUEUE_STREAM_TIMEOUT = '16m';
const SEAT_STREAM_TIMEOUT = '1m';

interface SseEvent {
  name: string;
  data: string;
}

// 첫 이벤트를 받으면 연결 성공으로 센다. onEvent가 true를 반환하면 스트림을 닫는다.
// 연결에 성공해도 이벤트 처리에 실패하면(removed·오류) false를 반환한다.
function consume(path: string, token: string, name: string, timeout: string, onEvent: (event: SseEvent) => boolean): boolean {
  const ticket = issueSseTicket(token);
  if (ticket === null) {
    sseConnectSuccess.add(false); // 티켓 발급 실패는 sse_ticket 요청에서 이미 오류로 센다
    return false;
  }
  let connected = false;
  let completed = false;
  const result = sse.open(`${BASE_URL}${path}?ticket=${ticket}`, { tags: { name }, timeout }, (client) => {
    client.on('event', (event: SseEvent) => {
      if (!event.name) return; // heartbeat 주석
      if (!connected) {
        connected = true;
        sseConnectSuccess.add(true);
      }
      if (onEvent(event)) {
        completed = true;
        client.close();
      }
    });
    client.on('error', () => client.close());
  });
  if (!connected) {
    sseConnectSuccess.add(false);
    sseFailures.add(1, { stream: name, status: String(result.status ?? 0) });
    if (__ENV.LOADTEST_DEBUG === '1') console.error(`SSE 연결 실패: ${name} ${result.status} ${result.error ?? ''}`);
  }
  if (!completed) unexpectedErrors.add(true);
  return completed;
}

export interface Admission {
  admissionToken: string;
  waitedMs: number;
}

// 순번 스트림을 열고 admitted까지 기다린다. removed·오류·시간 초과면 null이다.
export function waitForAdmission(scheduleId: number, token: string): Admission | null {
  const startedAt = Date.now();
  let admissionToken: string | null = null;
  const completed = consume(`/api/v1/streams/queues/${scheduleId}`, token, 'sse_queue', QUEUE_STREAM_TIMEOUT, (event) => {
    if (event.name === 'removed') return true; // 아래에서 실패로 센다
    if (event.name !== 'admitted') return false;
    admissionToken = (JSON.parse(event.data) as { admissionToken: string }).admissionToken;
    return true;
  });
  if (admissionToken === null) {
    if (completed) unexpectedErrors.add(true); // removed. 오류·시간 초과는 consume이 이미 센다
    return null;
  }
  return { admissionToken, waitedMs: Date.now() - startedAt };
}

// 스냅샷은 좌석 수천 개의 JSON이다. VU마다 전체를 객체로 파싱하면 k6 메모리가 크게 늘어서(1만 VU 규모)
// 예매 후보가 되는 AVAILABLE 좌석 ID만 정규식으로 뽑는다. 필드 순서는 realtime의 SeatSnapshot(seatId, status, holdExpiresAt)을 따른다.
const AVAILABLE_SEAT = /"seatId":(\d+),"status":"AVAILABLE"/g;

// 좌석 스트림은 snapshot 이벤트를 받으면 닫는다. 스냅샷의 AVAILABLE 좌석 ID를 반환하며, 실패하면 null이다.
export function readSeatSnapshot(scheduleId: number, token: string): number[] | null {
  let available: number[] | null = null;
  consume(`/api/v1/streams/schedules/${scheduleId}/seats`, token, 'sse_seats', SEAT_STREAM_TIMEOUT, (event) => {
    if (event.name !== 'snapshot') return false;
    available = [];
    for (const match of event.data.matchAll(AVAILABLE_SEAT)) available.push(Number(match[1]));
    return true;
  });
  return available;
}
