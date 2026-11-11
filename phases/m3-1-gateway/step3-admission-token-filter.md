# Step 3: admission-token-filter

- model: 기본 / reasoning_effort: high

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `gateway/src/main/kotlin/io/github/ticketing/gateway/route/GatewayRoutes.kt` (step 2)
- `gateway/src/main/kotlin/io/github/ticketing/gateway/auth/` (step 2, 회원 ID attribute와 회원 필수 필터)
- `gateway/src/main/kotlin/io/github/ticketing/gateway/support/ProblemResponses.kt` (step 2)
- `gateway/src/test/kotlin/io/github/ticketing/gateway/GatewayIntegrationTestSupport.kt`, `DownstreamStub` (step 2)
- `core/src/main/kotlin/io/github/ticketing/booking/adapter/provided/web/BookingController.kt`의 `CreateBookingRequest` (본문 형식 확인만)
- `docs/rules/backend.md § Gateway`

## 작업

`POST /api/v1/bookings`(예매 생성·좌석 선점)에 입장 토큰 검증을 붙인다.

### 계약 (ADR 0020)

- 헤더: `X-Admission-Token: <불투명 난수>`
- Redis: `queue:admission:<token>` Hash, 필드 `memberId`, `scheduleId`(둘 다 10진 문자열). TTL은 발급자(realtime)가 정한다. Gateway는 읽기만 하고 지우지 않는다(TTL 안에서는 재사용 가능).
- 요청 본문: `{"scheduleId": <Long>, "seatIds": [...]}`

### 1. 라우트

`GatewayRoutes`에 라우트 `core-bookings-create`(메서드 `POST`, 경로 정확히 `/api/v1/bookings`, 대상 core)를 `core-api`보다 먼저 평가되게 추가한다. 필터 순서: 회원 필수(step 2) → 입장 토큰 검증.

### 2. 필터: `gateway/src/main/kotlin/io/github/ticketing/gateway/admission/AdmissionTokenFilter.kt`

```kotlin
class AdmissionTokenFilter(redis: ReactiveStringRedisTemplate, jsonMapper: /* Jackson 3 매퍼 */) : GatewayFilter
```

검사 순서와 응답:

| 조건 | 응답 |
|---|---|
| 회원 ID 없음 | 401 `UNAUTHORIZED` (회원 필수 필터가 처리) |
| `X-Admission-Token` 없음·빈 값 | 403 `ADMISSION_TOKEN_REQUIRED` |
| Redis Hash 없음(만료 포함) | 403 `ADMISSION_TOKEN_INVALID` |
| Hash의 `memberId` ≠ JWT 회원 ID | 403 `ADMISSION_TOKEN_INVALID` |
| 본문이 16KB 초과, JSON 아님, `scheduleId` 없음·숫자 아님 | 400 `INVALID_REQUEST_BODY` |
| Hash의 `scheduleId` ≠ 본문 `scheduleId` | 403 `ADMISSION_TOKEN_INVALID` |
| 모두 일치 | core로 전달 |

- 본문은 Spring Cloud Gateway의 요청 본문 캐시 기능(`ServerWebExchangeUtils.cacheRequestBody` 등, 공식 문서로 확인)으로 한 번 읽고, core에는 **같은 바이트**를 그대로 보낸다. 본문을 다시 직렬화하지 않는다.
- core로 보내기 전에 `X-Admission-Token` 헤더를 지운다. 이유: 코어는 대기열을 모른다(AGENTS.md 설계 규칙 4).
- 본문 크기 상한(16KB)은 상수가 아니라 설정값으로 둔다.
- 거부 응답은 모두 `ProblemResponses`를 쓴다.
- 토큰 값은 로그에 남기지 않는다. 거부 시 `memberId`, 거부 코드만 debug로 남길 수 있다.

### 3. 테스트: `gateway/src/test/kotlin/io/github/ticketing/gateway/admission/AdmissionTokenFilterTests.kt`

`GatewayIntegrationTestSupport`를 상속한다. Redis에 Hash와 TTL을 직접 넣어 준비하고, 테스트마다 고유 토큰을 쓴다. 다룰 경우:

- 회원·회차가 일치하면 core 스텁이 요청을 받고, 받은 본문이 보낸 본문과 바이트 단위로 같으며 `X-Admission-Token`이 없다.
- 같은 토큰으로 두 번 요청해도 둘 다 통과한다(재사용 가능).
- 헤더 없음 → `ADMISSION_TOKEN_REQUIRED`, 없는 토큰 → `ADMISSION_TOKEN_INVALID`, 다른 회원의 토큰 → `ADMISSION_TOKEN_INVALID`, 다른 회차 → `ADMISSION_TOKEN_INVALID`. 모두 core 스텁에 도달하지 않는다.
- 깨진 JSON → 400 `INVALID_REQUEST_BODY`.
- JWT 없이 호출 → 401 `UNAUTHORIZED`.
- `POST /api/v1/bookings/1/payments`, `GET /api/v1/bookings/me`는 입장 토큰 없이 core에 도달한다.

## Acceptance Criteria

```bash
rg -q 'queue:admission:' gateway/src/main/kotlin
rg -q 'ADMISSION_TOKEN_REQUIRED' gateway/src/main/kotlin
rg -q 'core-bookings-create' gateway/src/main/kotlin/io/github/ticketing/gateway/route/GatewayRoutes.kt
./scripts/agent/test/run-tests.sh :gateway:test
./scripts/agent/test/check-summary.sh :gateway:ktlintCheck :gateway:detekt
```

## 검증 절차

1. `run-tests.sh :gateway:test --tests "*AdmissionTokenFilterTests"`부터 통과시킨 뒤 위 AC 전체를 실행한다.

## 금지사항

- 입장 토큰을 Gateway에서 지우거나(`DEL`, `GETDEL`) TTL을 바꾸지 마라. 이유: 선점 실패 후 재시도에 같은 토큰을 쓰며, 수명은 발급자(realtime)가 관리한다.
- 결제·취소·조회 등 `POST /api/v1/bookings` 외의 경로에 입장 토큰을 요구하지 마라. 이유: 선점 뒤 토큰이 만료돼도 결제를 끝낼 수 있어야 한다.
- 코어 코드나 API(`/api/v1/bookings` 경로, 본문 형식)를 바꾸지 마라. 이유: Gateway 사정으로 코어 API를 바꾸지 않기로 했다(ADR 0020).
- 본문을 파싱한 객체로 다시 직렬화해 보내지 마라. 이유: 필드 순서·숫자 표현이 바뀌어 코어가 받는 요청이 달라진다.
- 필터에 좌석 수·회차 상태 같은 비즈니스 검증을 넣지 마라. 이유: `docs/rules/backend.md § Gateway` (필터는 검증과 라우팅만).
- 기존 테스트를 깨뜨리지 마라.
