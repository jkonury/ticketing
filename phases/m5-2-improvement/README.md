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
