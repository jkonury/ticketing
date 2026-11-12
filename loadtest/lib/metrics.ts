import { Counter, Rate, Trend } from 'k6/metrics';

export const queueWait = new Trend('queue_wait', true);
export const sseConnectSuccess = new Rate('sse_connect_success');
export const bookingConflict = new Counter('booking_conflict');
export const bookingConfirmed = new Counter('booking_confirmed');
export const seatsConfirmed = new Counter('seats_confirmed');
export const confirmLatency = new Trend('confirm_latency', true);
// 예상 응답: 2xx, 409 SEAT_ALREADY_HELD, 429 RATE_LIMITED. 그 밖의 4xx·5xx·네트워크 오류는 오류다.
export const unexpectedErrors = new Rate('unexpected_errors');

// 진단용. 결제 후 확정되지 않은 예매의 최종 상태(status 태그), 좌석이 없어 예매하지 못한 VU 수
export const bookingNotConfirmed = new Counter('booking_not_confirmed');
export const soldOutExits = new Counter('sold_out_exits');
// 첫 이벤트를 받지 못한 스트림. stream·status(HTTP 상태, 연결 오류는 0) 태그로 원인을 구분한다
export const sseFailures = new Counter('sse_failures');
// 예상하지 못한 응답의 원인 구분용. name·status(네트워크 오류는 0) 태그
export const unexpectedResponses = new Counter('unexpected_responses');
