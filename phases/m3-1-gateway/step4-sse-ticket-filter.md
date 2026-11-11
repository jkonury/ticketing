# Step 4: sse-ticket-filter

- model: 기본 / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `gateway/src/main/kotlin/io/github/ticketing/gateway/route/GatewayRoutes.kt` (step 2·3)
- `gateway/src/main/kotlin/io/github/ticketing/gateway/auth/` (step 2, 회원 ID attribute)
- `gateway/src/main/kotlin/io/github/ticketing/gateway/support/ProblemResponses.kt` (step 2)
- `gateway/src/test/kotlin/io/github/ticketing/gateway/GatewayIntegrationTestSupport.kt`, `DownstreamStub` (step 2)
- `core/src/main/kotlin/io/github/ticketing/member/service/SseTicketService.kt` (step 1, 키 형식 확인만)

## 작업

브라우저가 BFF를 거치지 않고 직접 여는 SSE 경로(`/api/v1/streams/**`)에 SSE 티켓 검증과 CORS를 붙인다.

### 계약 (ADR 0020)

- 브라우저: `GET /api/v1/streams/...?ticket=<ticket>` (EventSource는 헤더를 붙일 수 없어 쿼리로 받는다)
- Redis: `sse:ticket:<ticket>` → 회원 ID 문자열, TTL 30초. 코어 `member`가 발급한다.
- 1회용: Gateway가 `GETDEL`로 읽으면서 지운다.

### 1. 필터: `gateway/src/main/kotlin/io/github/ticketing/gateway/sse/SseTicketFilter.kt`

```kotlin
class SseTicketFilter(redis: ReactiveStringRedisTemplate) : GatewayFilter
```

- `ticket` 쿼리가 없거나, `GETDEL` 결과가 없거나, 값이 숫자가 아니면 401 `SSE_TICKET_INVALID`.
- 성공하면 realtime으로 보내는 요청에 `X-Member-Id: <회원 ID>`를 붙이고, URI에서 `ticket` 쿼리 파라미터만 지운다(다른 쿼리, 예: `lastEventId`는 유지).
- `Last-Event-ID` 요청 헤더는 그대로 전달한다(M3-2 재연결 처리에 쓰인다).
- 이 경로는 JWT를 요구하지 않는다. `Authorization` 헤더가 있어도 회원 ID는 티켓에서만 얻는다.
- `realtime-streams` 라우트에 이 필터를 붙인다.

### 2. CORS

- `/api/v1/streams/**`에만 `ticketing.gateway.cors.allowed-origins`(기본 `http://localhost:3000`)를 허용한다. 메서드는 `GET`, 자격 증명(쿠키)은 허용하지 않는다(티켓이 인증 수단이다).
- 다른 경로에는 CORS를 열지 않는다. 이유: 일반 API는 BFF(서버)만 호출한다.
- Spring Cloud Gateway의 전역·라우트별 CORS 설정 방식을 공식 문서로 확인해 그중 하나를 쓴다.

### 3. 테스트: `gateway/src/test/kotlin/io/github/ticketing/gateway/sse/SseTicketFilterTests.kt`

`GatewayIntegrationTestSupport`를 상속한다. Redis에 `sse:ticket:<고유값>`을 직접 넣어 준비한다. 다룰 경우:

- 유효한 티켓이면 realtime 스텁이 `X-Member-Id`를 받고, 받은 쿼리에 `ticket`이 없으며 다른 쿼리는 남아 있다. Redis 키가 지워졌다.
- 같은 티켓으로 두 번째 요청 → 401 `SSE_TICKET_INVALID`.
- 티켓 없음, 없는 티켓, 숫자가 아닌 값 → 401 `SSE_TICKET_INVALID`. 스텁에 도달하지 않는다.
- 클라이언트가 보낸 `X-Member-Id`는 티켓의 회원 ID로 대체된다.
- `Origin: http://localhost:3000` preflight(`OPTIONS`)가 `/api/v1/streams/**`에서는 허용되고, `/api/v1/bookings/me`에서는 허용 헤더가 없다.

## Acceptance Criteria

```bash
rg -q 'sse:ticket:' gateway/src/main/kotlin
rg -q 'SSE_TICKET_INVALID' gateway/src/main/kotlin
./scripts/agent/test/run-tests.sh :gateway:test
./scripts/agent/test/check-summary.sh :gateway:ktlintCheck :gateway:detekt
```

## 검증 절차

1. `run-tests.sh :gateway:test --tests "*SseTicketFilterTests"`부터 통과시킨 뒤 위 AC 전체를 실행한다.

## 금지사항

- `GET` 후 `DEL`처럼 두 번의 명령으로 티켓을 소비하지 마라. 이유: 동시 요청 두 개가 같은 티켓을 둘 다 통과시킬 수 있다. `GETDEL` 한 번으로 원자적으로 처리한다.
- 티켓을 하위 서비스로 전달하지 마라(쿼리에 남기지 마라). 이유: 이미 소비된 인증 수단이 realtime 접근 로그에 남는다.
- 티켓 값을 로그에 남기지 마라.
- 모든 경로에 CORS를 열지 마라. 이유: 브라우저 직접 접근의 예외는 SSE뿐이다(ADR 0014).
- 기존 테스트를 깨뜨리지 마라.
