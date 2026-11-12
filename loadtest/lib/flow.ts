// 두 시나리오가 공유하는 VU 흐름: 대기열 → 입장 → 좌석 선택 → 예매 → 결제 → 확정 폴링
import { sleep } from 'k6';
import exec from 'k6/execution';
import { bookingDetail, createBooking, enterQueue, ok, payBooking } from './api.ts';
import { memberIdForVu } from './data.ts';
import { signAccessToken } from './jwt.ts';
import {
  bookingConfirmed,
  bookingConflict,
  bookingNotConfirmed,
  confirmLatency,
  queueWait,
  seatsConfirmed,
  soldOutExits,
} from './metrics.ts';
import { readSeatSnapshot, waitForAdmission } from './sse.ts';

const MAX_SEATS_PER_BOOKING = 4;
const CONFIRM_POLL_ATTEMPTS = 30;

export interface FlowConfig {
  scheduleId: number;
  // 있으면 이 좌석에서만 고르고 좌석 스트림은 읽지 않는다(시나리오 B). 없으면 스냅샷의 AVAILABLE 좌석에서 고른다.
  fixedCandidates?: number[];
  // 409 SEAT_ALREADY_HELD 뒤 다시 시도하는 횟수
  conflictRetries: number;
  // epoch ms. 입장 뒤 이 시각까지 기다렸다가 예매를 시작한다
  startAt?: number;
}

function idempotencyKey(): string {
  return `k6-${exec.vu.idInTest}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

// 1~4 사이의 장수를 고르고, 후보에서 그만큼 서로 다른 좌석을 무작위로 뽑는다
function pickSeats(candidates: number[]): number[] {
  const count = Math.min(1 + Math.floor(Math.random() * MAX_SEATS_PER_BOOKING), candidates.length);
  const pool = candidates.slice();
  for (let i = 0; i < count; i++) {
    const j = i + Math.floor(Math.random() * (pool.length - i));
    [pool[i], pool[j]] = [pool[j], pool[i]];
  }
  return pool.slice(0, count);
}

function awaitConfirmation(token: string, bookingId: number, seatCount: number): void {
  const paidAt = Date.now();
  let status = 'PENDING';
  for (let attempt = 0; attempt < CONFIRM_POLL_ATTEMPTS; attempt++) {
    sleep(1);
    const detail = bookingDetail(token, bookingId);
    if (!ok(detail.status)) continue;
    status = (detail.json() as { status: string }).status;
    if (status === 'CONFIRMED') {
      bookingConfirmed.add(1);
      seatsConfirmed.add(seatCount);
      confirmLatency.add(Date.now() - paidAt);
      return;
    }
    if (status !== 'PENDING') break;
  }
  bookingNotConfirmed.add(1, { status });
}

export function runBookingFlow(config: FlowConfig): void {
  const token = signAccessToken(memberIdForVu(), 'USER');

  const enteredAt = Date.now();
  if (!ok(enterQueue(token, config.scheduleId).status)) return;
  const admission = waitForAdmission(config.scheduleId, token);
  if (admission === null) return;
  queueWait.add(Date.now() - enteredAt);
  // 대기열 이탈은 하지 않는다. 입장 토큰 TTL로 정리된다

  if (config.startAt !== undefined) {
    const remaining = config.startAt - Date.now();
    if (remaining > 0) sleep(remaining / 1000);
  }

  for (let attempt = 0; attempt <= config.conflictRetries; attempt++) {
    let candidates = config.fixedCandidates;
    if (candidates === undefined) {
      const available = readSeatSnapshot(config.scheduleId, token);
      if (available === null) return;
      candidates = available;
      if (candidates.length === 0) {
        soldOutExits.add(1);
        return;
      }
    }
    const seatIds = pickSeats(candidates);
    const response = createBooking(token, admission.admissionToken, idempotencyKey(), config.scheduleId, seatIds);
    if (ok(response.status)) {
      const { bookingId } = response.json() as { bookingId: number };
      if (!ok(payBooking(token, bookingId, idempotencyKey()).status)) return;
      awaitConfirmation(token, bookingId, seatIds.length);
      return;
    }
    if (response.status === 409 && String(response.body).includes('SEAT_ALREADY_HELD')) {
      bookingConflict.add(1);
      continue;
    }
    return;
  }
}
