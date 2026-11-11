# Step 5: rate-limit

- model: 기본 / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `gateway/src/main/kotlin/io/github/ticketing/gateway/route/GatewayRoutes.kt` (step 2~4)
- `gateway/src/main/kotlin/io/github/ticketing/gateway/auth/` (step 2, 회원 ID attribute)
- `gateway/src/main/kotlin/io/github/ticketing/gateway/support/ProblemResponses.kt` (step 2)
- `gateway/src/test/kotlin/io/github/ticketing/gateway/GatewayIntegrationTestSupport.kt` (step 2)
- `gateway/src/main/resources/application.yml`

## 작업

Spring Cloud Gateway 내장 `RedisRateLimiter`(토큰 버킷, Redis Lua)로 요청 수를 제한한다.

**`RedisRateLimiter`의 생성 방식, 설정 키, 응답 헤더(`X-RateLimit-*`), Redis 장애 시 동작은 Spring Cloud Gateway Server WebFlux 공식 문서와 사용 중인 jar의 소스로 확인하라.**

### 1. 한도 설정

`ticketing.gateway.rate-limit.<이름>.replenish-rate`, `.burst-capacity`로 받는다(`@ConfigurationProperties`).

| 이름 | 적용 라우트 | 기본값 (초당 보충 / 버스트) |
|---|---|---|
| `default` | `core-api`, `realtime-queues` | 20 / 40 |
| `booking-create` | `core-bookings-create` | 2 / 5 |
| `streams` | `realtime-streams` | 5 / 10 |

라우트마다 버킷이 따로다(같은 회원이라도 라우트별로 센다).

### 2. 키 결정: `gateway/src/main/kotlin/io/github/ticketing/gateway/ratelimit/`

```kotlin
class RateLimitKeyResolver(...) : KeyResolver   // "member:<id>" 또는 "ip:<주소>"
```

- 회원 ID attribute(step 2에서 검증된 JWT, 또는 step 4의 SSE 티켓)가 있으면 `member:<id>`, 없으면 `ip:<클라이언트 IP>`.
- 클라이언트 IP는 `XForwardedRemoteAddressResolver.maxTrustedIndex(n)`으로 구한다. `n`은 설정 `ticketing.gateway.rate-limit.trusted-proxies`(기본 1). 이유: 일반 요청은 BFF를 거쳐 오므로 원격 주소만 쓰면 비로그인 사용자 전체가 BFF IP 하나의 버킷을 나눠 쓴다. BFF는 M4에서 `X-Forwarded-For`를 붙인다.
- 키 결정은 회원 ID attribute가 채워진 뒤에 일어나야 한다. `realtime-streams`에서는 SSE 티켓 필터 다음에 rate limit을 적용한다. 순서를 테스트로 확인한다.

### 3. 필터

- 초과 시 429 `RATE_LIMITED` ProblemDetail로 응답하고 하위로 보내지 않는다. 내장 `RequestRateLimiter` 필터가 본문을 쓰지 못하면 `RateLimiter.isAllowed(routeId, key)`를 호출하는 얇은 `GatewayFilter`를 만들어 쓴다.
- `RedisRateLimiter`가 돌려주는 `X-RateLimit-*` 헤더를 응답에 포함한다.
- Redis 오류 시에는 라이브러리 기본 동작(허용)을 따른다. 이 동작을 `docs`가 아니라 완료 보고에 적는다(step 6에서 문서화).

### 4. 테스트: `gateway/src/test/kotlin/io/github/ticketing/gateway/ratelimit/RateLimitTests.kt`

`GatewayIntegrationTestSupport`를 상속한다. 테스트 한도는 support의 테스트 프로퍼티에서 작게 잡되, 기존 테스트가 한도에 걸리지 않게 테스트마다 고유 회원 ID·IP를 쓴다(이미 그렇지 않은 기존 테스트가 있으면 고유 값을 쓰도록 고친다). 시간 경과를 `Thread.sleep`으로 기다리지 않는다. 다룰 경우:

- 같은 회원이 `booking-create` 버스트를 넘기면 429 `RATE_LIMITED`이고, 넘긴 요청은 core 스텁에 도달하지 않는다.
- 다른 회원은 영향을 받지 않는다.
- 비로그인 요청은 `X-Forwarded-For`의 클라이언트 IP별로 따로 센다.
- 응답에 `X-RateLimit-Remaining` 헤더가 있다.

## Acceptance Criteria

```bash
rg -q 'RATE_LIMITED' gateway/src/main/kotlin
rg -q 'RedisRateLimiter' gateway/src/main/kotlin
./scripts/agent/test/run-tests.sh :gateway:test
./scripts/agent/test/check-summary.sh :gateway:ktlintCheck :gateway:detekt
```

## 검증 절차

1. `run-tests.sh :gateway:test --tests "*RateLimitTests"`부터 통과시킨 뒤 위 AC 전체를 실행한다.

## 금지사항

- rate limit 알고리즘(Lua 스크립트)을 직접 구현하지 마라. 이유: 내장 `RedisRateLimiter`를 쓰기로 합의했다(ADR 0020).
- 인스턴스 메모리 카운터(예: Bucket4j 로컬, `ConcurrentHashMap`)를 쓰지 마라. 이유: Gateway를 여러 인스턴스로 띄우면 한도가 인스턴스 수만큼 늘어난다.
- `X-Forwarded-For`를 무조건 신뢰하지 마라. 이유: 클라이언트가 값을 바꿔 한도를 우회한다. 신뢰 홉 수만큼만 쓴다.
- 서명 검증 없이 JWT `sub`를 키로 쓰지 마라. 이유: 위조된 `sub`로 버킷을 바꿔 가며 우회한다. step 2의 attribute만 쓴다.
- 기존 테스트를 깨뜨리지 마라.
