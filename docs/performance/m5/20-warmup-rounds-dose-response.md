# M5 외부 예열 회차 수 용량-반응(E3)

> 허브: [M5-2 개선 및 측정 인계](../m5-improvements.md) · 이전: [외부·내부 예열의 TCP·연결 상태 대조](19-warmup-tcp-diagnosis.md) · 계획: [내부·외부 예열 격차 실험](../../plans/hot-seats-internal-warmup-gap-experiments.md) 2절 E3

## 결과

외부 `--warmup`의 예열 회차만 바꿔 예매 생성 p95를 비교했다. 회차가 늘수록 대체로 줄지만, 같은 조건 안에서도 실행 간 편차가 매우 커서 방향만 신뢰할 수 있다.

| 예열 회차 | 실행 | 예매 p50 (ms) | p95 (ms) | p99 (ms) | 실패 |
| --- | --- | --- | --- | --- | --- |
| 1 | 1 | 707 | 1,599 | 1,637 | 0 |
| 1 | 2 | 648 | 927 | 981 | 0 |
| 3 | 1 | 444 | 1,350 | 1,394 | 0 |
| 3 | 2 | 461 | 593 | 635 | 0 |
| 15 (기존 [19](19-warmup-tcp-diagnosis.md)) | 2회 | - | 435·436 | - | 0 |
| 내부 예열 ([18](18-warmup-traffic-mix.md)) | 3회 | - | 750~772 | - | 0 |

전 실행이 300ms 기준에 미달했다(러너 종료 코드 1). 정합성·격리·서버 차분은 모두 유효했다.

## 해석

- **p50은 회차에 반응한다.** 1회 648~707ms, 3회 444~461ms로 두 실행이 서로 가깝다. 예열 양이 늘면 일반적인 요청 지연이 내려간다.
- **p95는 실행 편차가 지배한다.** 같은 3회 예열에서 593ms와 1,350ms가 나왔다. 각 조건 2회뿐이라 평균이나 중앙값을 결론에 쓰지 않는다. 두 조건 모두 첫 번째 실행이 두 번째보다 나빴지만 원인은 확인하지 못했다.
- 계획 문서의 판정 기준으로 보면 "1회만 예열해도 약 450ms 근처"는 성립하지 않았다(1회 p50 650ms 이상). 격차에는 **예열 양**도 기여한다. 다만 15회에서 내부 계열(750~830ms)보다 훨씬 낮은 이유가 양만인지 경로 차이인지는 이 실험으로 분리하지 못한다. 내부 예열은 외부 15회와 요청 수가 크게 다르고(회차당 1,000건 × 횟수), 빠진 경로도 있다.
- 따라서 [ADR 0032](../../adr/0032-warmup-traffic-mix.md)의 내부 예열을 **횟수·혼합 비율 쪽으로 늘리는 방향**은 근거가 생겼다. 경로 보강([ADR 0033](../../adr/0033-warmup-rate-limiter-path.md)의 rate limiter 예열 등)과는 서로 대체가 아니다.

## 측정 조건

HEAD `c4259e3` 서비스 코드. 같은 작업 트리의 Gateway rate limiter 예열 변경([ADR 0033](../../adr/0033-warmup-rate-limiter-path.md))은 `STARTUP_WARMUP` 속성이 붙은 내부 예열 요청에서만 동작하므로 외부 `--warmup` 계열에는 영향이 없다. Gatling 힙 2g, hot-seats 1,000명, 관측 ON·Scouter OFF·JFR OFF, 발생기 CPU 0-3·앱 CPU 4-15.

```bash
./scripts/loadtest/run.sh --scenario hot-seats --tool gatling --gatling-heap 2g --runs 2 \
  --observability --no-scouter --warmup --warmup-rounds <1|3> --booking-p95-ms 300 --k6-cpus 0-3 --app-cpus 4-15
```

실행 디렉터리(git 제외): 1회 `build/loadtest/runs/20261117-003047-hot-seats/{1,2}`, 3회 `build/loadtest/runs/20261117-003632-hot-seats/{1,2}`. p95는 각 `gatling-summary.json`의 `.requests.booking_create`다.

## 한계

- 조건당 2회라 p95 평균·분산을 추정할 수 없다. 3회와 15회의 차이도 유의 검정 대상이 아니다.
- 15회 값은 이전 세션 실행이며 같은 날 재측정이 아니다.
- Gateway rate limiter 예열의 효과는 이 문서에서 측정하지 않았다(내부 계열 JFR OFF 3회 필요).

## 다음

1. 내부 예열 JFR OFF 3회로 ADR 0033의 효과를 측정한다.
2. 내부 예열의 횟수·혼합 비율을 늘리는 안을 ADR 0032 보강으로 사용자와 합의한다.
