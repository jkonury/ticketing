# ADR 0033: Gateway 내부 예열에서 rate limiter 경로 실행

- 상태: 합의
- 작성일: 2026-11-17
- 합의일: 2026-11-17
- 결정 주체: 사용자 요청 반영 (예열 커버리지 조사 후 1순위로 진행 지시)
- 대체: 없음
- 대체됨·일부 변경: 없음
- 관련: [ADR 0029](0029-layered-startup-warmup.md), [ADR 0032](0032-warmup-traffic-mix.md), [계획](../plans/hot-seats-internal-warmup-gap-experiments.md)

## 배경

내부 예열(readiness 전)과 외부 예열의 p95 격차를 좁히는 실험에서 Gateway stage 지연이 두 계열 사이에 2~25배 차이 났다. `RateLimitFilter`는 예열 요청이 회원 -1의 버킷을 소진해 429가 나지 않도록 예열 요청에서 limiter 전체를 건너뛴다. 그래서 `RedisRateLimiter.isAllowed`(Redis 스크립트 실행, 응답 매핑, 리액티브 체인)가 내부 예열에서 한 번도 실행되지 않고, 첫 실제 요청들이 이 경로의 인터프리터·콜드 비용을 낸다. 이 상관은 확인했지만 인과는 아직 측정하지 않았다.

## 결정

1. 예열 요청(Gateway가 진짜 예열 헤더에만 붙이는 `STARTUP_WARMUP` 속성)도 `limiter.isAllowed(routeId, key)`를 실제로 실행한다.
2. 키는 회원 -1이 아니라 예열 전용 풀 `warmup:0..1023`을 순환해 쓴다. 버킷 소진을 피하고 `member:`·`ip:` 키 공간과 겹치지 않는다.
3. limiter 결과는 무시한다. 거절되어도 429를 만들지 않고, 오류가 나도 요청을 통과시킨다. 응답에 `X-RateLimit-*` 헤더를 붙이지 않는다.
4. 이 호출은 `ticketing.gateway.stage` 타이머에 기록하지 않는다. 측정 지표에 예열 값이 섞이지 않게 한다.
5. 한도 설정(`RateLimitProperties`)과 예열 신뢰 경계(헤더 검증)는 바꾸지 않는다. `ticketing.gateway.rate-limit.enabled=false`이면 예열도 limiter를 호출하지 않는다.

## 검토한 대안

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| 예열 전용 limiter 설정(큰 burst) | 설정 항목이 늘고 운영 한도와 다른 설정을 한 번 더 관리해야 한다. 결과를 무시하면 필요 없다. |
| 회원 -1 키를 그대로 쓰고 429 허용 | 예열 응답 판정이 흔들리고, 소진된 버킷은 허용·거절 경로 중 한쪽만 실행한다. |
| 현행(건너뜀) 유지 | 격차의 후보 원인을 검증할 수 없다. |

## 결과

- 얻는 것: Gateway rate limiter 경로가 예열되어 첫 실제 요청의 Gateway stage 지연이 줄 것으로 기대한다(측정 후 확정).
- 감수하는 것: 예열 중 Redis 호출이 요청당 1회 늘고 예열 시간이 소폭 늘 수 있다. 예열 키 최대 1,024개가 limiter TTL 동안 Redis에 남는다.
- 바뀌는 문서·지침: 없음 (`docs/architecture.md`에 limiter 예열 언급 없음)

## 검증

- `RateLimitFilterWarmupTests`: 거절·오류에도 통과, limiter 비활성 시 미호출.
- `RateLimitTests`: 일반 요청의 한도 동작 불변.
- 효과는 hot-seats 내부 계열 JFR OFF 3회로 측정해 `ticketing_gateway_stage_seconds`와 전체·성공 p95를 현재 내부 계열(750~830ms)과 비교한다.

## 변경 이력

- 2026-11-17: 작성, 합의
