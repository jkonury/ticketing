# Phase M5-2: 병목 개선과 전후 비교

M5-1 기준 측정을 바탕으로 대기열·예매·Gateway 병목과 정합성을 검증하고 개선한다. 각 step은 독립 세션에서 해당 문서만 실행 지시서로 사용하며, 순서대로 진행하고 선행 step의 완료 기준과 결과를 확인한다. 각 문서에는 공통 측정 계약과 검증 절차가 포함되어 있다.

결과와 인계는 Step 1에서 만드는 `docs/performance/m5-improvements.md`에 누적한다. 기존 `docs/performance/m5-baseline.md`는 보존한다. M5 완료 기준은 부하 중 중복 판매 0건과 개선 전후 수치 문서화다. 미완료 상태의 로드맵을 계획 작성만으로 체크하지 않는다.

| 순서 | 실행 문서 |
|---|---|
| 1 | [close-measurement-gaps](./step1-close-measurement-gaps.md) |
| 2 | [fix-queue-races](./step2-fix-queue-races.md) |
| 3 | [separate-warmup-measurements](./step3-separate-warmup-measurements.md) |
| 4 | [improve-queue-throughput](./step4-improve-queue-throughput.md) |
| 5 | [reduce-booking-connection-wait](./step5-reduce-booking-connection-wait.md) |
| 6 | [resolve-gateway-delays](./step6-resolve-gateway-delays.md) |
| 7 | [verify-compensation-under-load](./step7-verify-compensation-under-load.md) |
| 8 | [publish-m5-results](./step8-publish-m5-results.md) |

원본 계획: [M5-2 병목 개선과 전후 비교](../../docs/plans/m5-2-improvements.md).

## 상태 (2026-11-16 정리)

- 위 8단계는 **기록으로 대체**했다. 실행 결과는 [단계별 기록 허브](../../docs/performance/m5-improvements.md)의 「단계 요약」을 따른다. 각 단계 문서는 k6 기준 전제라 그대로 다시 실행하지 않는다.
- Step 1~8 대응: 1 [01](../../docs/performance/m5/01-step1-measurement-gaps.md) · 2 [05](../../docs/performance/m5/05-step2-queue-races.md) · 3 [06](../../docs/performance/m5/06-step3-warmup-separation.md) · 4 [07](../../docs/performance/m5/07-step4-queue-throughput.md) · 5 [08](../../docs/performance/m5/08-step5-booking-connection-wait.md) · 6 [09](../../docs/performance/m5/09-step6-gateway-delay.md) · 7 [10](../../docs/performance/m5/10-step7-compensation-under-load.md) · 8 [11](../../docs/performance/m5/11-step8-final-report.md)
- 이후 hot-seats 측정은 Gatling과 내부 예열로 진행했다([Gatling 인계](../../docs/plans/hot-seats-gatling-handoff.md), [내부 예열 계획](../../docs/plans/hot-seats-internal-warmup-cold-900ms.md)).
- 300ms 장기 목표는 미달로 후속 과제로 이관했다.
