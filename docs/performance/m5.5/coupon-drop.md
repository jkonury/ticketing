# coupon-drop 측정 결과 (M5.5-3)

## 목적

1만 명이 LIMITED 쿠폰 100장을 같은 시각에 받을 때 (1) 초과·중복 발급이 없고 Redis–DB 대사가 맞는지, (2) 두 발급 전략(redis: Lua + 동기 INSERT, db: 조건부 UPDATE)의 차이, (3) 병목이 어디인지 확인한다. 같은 시나리오로 hot-seats 회귀(중복 판매 0건)도 확인한다.

## 환경과 방법

- 한 PC에서 core·Gateway·realtime·부하 도구를 함께 실행했다. 모든 회차는 1회씩 측정했다.
- 부하 도구는 Gatling(`CouponDropSimulation`)이다. `./scripts/loadtest/run.sh --scenario coupon-drop --tool gatling --coupon-strategy redis|db`로 실행했다. k6 `coupon-drop.ts`는 남아 있으나 이번 측정에 쓰지 않았다.
- 원시 결과는 `build/loadtest/runs/<디렉터리>/1`에 있다(git 제외). 아래 표의 디렉터리 이름은 그 `<디렉터리>`다.
- 정합성 판정: 발급 100, `(정의, 회원)` 중복 0, redis는 재고 0·Set 100 일치, db는 `remaining_quantity` 0, 응답 분류는 발급 100·소진 9,900·기타 0.

## 단계별 결과

모든 회차에서 정합성 검사를 통과했다(2단계 db 회차는 응답 분류의 기타가 있어 분류만 예외). 각 칸은 redis / db 순서다.

### 1단계: 동시 연결(atOnce, k6와 같은 방식)

| 항목 | redis (20261118-025922) | db (20261118-030125) |
|---|---|---|
| 클라이언트 p95 | 12.1s | 12.2s |
| Gateway 서버 p95 | 4.3s | 4.0s |
| ratelimit-redis p95 | 3.3s | 3.8s |
| core 받기 p95 | 2.82s | 1.16s |
| Hikari 획득 | 12,131회, p95 1.25s | 20,021회, p95 0.58s |
| ListenOverflows | 34,666 | 23,614 |

- 원인 1: 1만 TCP 연결을 동시에 만들어 Gateway listen 큐가 넘쳤고 SYN 재전송(1·2·4초)이 클라이언트 지연을 지배했다.
- 원인 2: 받기 서비스가 요청마다 정의를 DB에서 조회해 소진 응답도 커넥션을 기다렸다.

### 2단계: 사전 연결

20초 ramp 동안 `GET /api/v1/coupons`로 연결을 열어 두고 40초 뒤 동시에 받는다(지각 0명).

| 항목 | redis (030633) | db (030753) |
|---|---|---|
| 클라이언트 p95 | 6.9s | 7.2s (기타 1,236건) |
| Gateway proxy p95 | 6.3s | 30s |
| ListenOverflows | 9,589 | 25,701 |

- 원인: Gateway 기본 풀(elastic)이 동시 요청마다 core로 새 연결을 만들어 core Tomcat accept 큐(100)가 넘쳤다. db 회차의 기타 1,236건은 Gateway→core connect 30초 타임아웃이다.

### 3단계: Gateway 전역 fixed 풀 100 (b2f09e9)

| 항목 | redis (031351) | db (031510) |
|---|---|---|
| 클라이언트 p95 | 5.9s | 6.5s |
| core 받기 p95 | 56ms | 99ms |
| Hikari 획득 p95 | 7ms | 46ms |
| Gateway 풀 대기 p95 | 4.2s | 4.2s |
| ListenOverflows | 0 | 0 |

- hot-seats 회귀(031629): 풀 상한이 realtime SSE(장시간 연결)에도 걸려 SSE 3건이 PrematureClose로 실패했고 입장은 997/1000, 동시 경합 조건을 채우지 못했다. 중복 판매는 0이다.

### 4단계: core 호스트에만 풀 상한 100 (13157d0, `forRemoteHost`)

| 항목 | redis (032646) | db (032805) |
|---|---|---|
| 클라이언트 p95 | 5.88s | 6.35s |
| core 받기 p95 | 58ms | 94ms |
| Gateway proxy p95 | 4.2s | 5.4s |
| ratelimit-redis p95 | 0.35s | 0.35s |
| ListenOverflows | 0 | 0 |

- hot-seats(032923): 입장·정시 준비 1000/1000, SSE 실패 0, 확정 98석, 중복 0, 예매 생성 p95 1.91s. 이전 elastic 회차(030250)는 2.48s였다. 한도 1.5s에는 못 미치며 이는 M5 장기 과제다.

## 결론

- 완료 기준 (3) coupon-drop 초과·중복 0, Redis–DB 대사 일치와 (4) hot-seats 중복 판매 0을 충족했다. (1)(2)는 M5.5-2에서 충족했다.
- coupon_claim p95 1초 목표는 미달로 마무리한다. 남은 지연은 Gateway의 core 풀 대기이며, 한 PC에서 앱·Gateway·부하 도구가 자원을 나눠 쓰는 영향이 있다.
- 전략 비교: core p95는 redis가 낮다(58ms 대 94ms). 다만 1회 측정이라 근거가 약하다. db 방식은 Hikari 획득이 redis보다 1.5배였다는 점만 확인했다.
- 병목은 앱 로직이 아니라 연결 수립(1단계), Gateway→core 연결 폭주(2단계), 풀 대기(3·4단계) 순으로 이동했다.
- rate limit `coupon-claim`(보충 1, 버스트 3)은 임시값으로 확정한다. 이번 시나리오는 회원당 1회라 429가 0건이어서 수치는 검증하지 못했다.

## 후속 항목

- coupon_claim p95 1초: Gateway의 core 풀 대기를 줄인다.
- 소진 경로 개선: 정의 캐시로 소진 응답이 DB에 접근하지 않게 한다.
- `coupon-claim` rate limit 수치를 검증하는 테스트를 추가한다.
- redis·db 전략 비교는 반복 측정으로 보강한다.

## 관련 변경

2cc628c(선착순 받기 두 전략), b775249(보정 Job, 보수적 방향만), c8d2abc(Gateway 라우트·rate limit), effeca5·3adae72·e490a86(Gatling 하네스), b2f09e9·13157d0(Gateway 연결 풀).
