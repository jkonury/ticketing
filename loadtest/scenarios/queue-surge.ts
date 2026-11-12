// 시나리오 A: 대기열에 한꺼번에 몰린 사용자가 입장해 좌석을 선점·결제한다(ADR 0024)
import { runBookingFlow } from '../lib/flow.ts';
import { scheduleId } from '../lib/data.ts';
import { systemTags, thresholds, vus } from '../lib/options.ts';

export const options = {
  discardResponseBodies: true,
  systemTags,
  scenarios: {
    queue_surge: {
      executor: 'per-vu-iterations',
      vus: vus(10000),
      iterations: 1,
      maxDuration: '15m',
    },
  },
  thresholds,
};

export default function () {
  runBookingFlow({ scheduleId: scheduleId(), conflictRetries: 2 });
}
