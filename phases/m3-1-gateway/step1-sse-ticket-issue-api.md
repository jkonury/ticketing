# Step 1: sse-ticket-issue-api

- model: 기본 / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `core/src/main/kotlin/io/github/ticketing/member/web/AuthController.kt`, `member/web/MemberResponses.kt`
- `core/src/main/kotlin/io/github/ticketing/member/service/AuthService.kt` (서비스 작성 방식 참고)
- `core/src/main/kotlin/io/github/ticketing/member/security/SecurityConfig.kt` (인가 규칙)
- `core/src/test/kotlin/io/github/ticketing/member/MemberAuthIntegrationTests.kt`, `core/src/test/kotlin/io/github/ticketing/IntegrationTestSupport.kt`
- `docs/rules/backend.md § API`, `§ 보안`, `§ 테스트`

## 작업

Gateway가 검증할 SSE 티켓을 코어 `member` 모듈이 발급한다. `member`는 헥사고날 모듈이 아니므로 기존 `web` / `service` 패키지 구조를 따른다.

### 1. 서비스: `core/src/main/kotlin/io/github/ticketing/member/service/SseTicketService.kt`

```kotlin
data class SseTicket(val ticket: String, val expiresAt: Instant)

class SseTicketService(redis: StringRedisTemplate, clock: Clock, /* TTL 설정 */) {
    fun issue(memberId: Long): SseTicket
}
```

- 티켓은 `SecureRandom` 32바이트를 URL-safe Base64(패딩 없음)로 인코딩한다. 쿼리 문자열에 그대로 넣기 위함이다.
- Redis `SET sse:ticket:<ticket> <memberId> PX <ttl>`. TTL 기본 30초, 설정 키 `ticketing.sse-ticket.ttl`(`@ConfigurationProperties`, `member` 패키지 안).
- 티켓 값과 키는 로그에 남기지 않는다(회원 ID만 로깅 가능).
- `Clock` 빈이 이미 있으면 그것을 쓴다(`rg -n 'fun clock' core/src/main`으로 확인).

### 2. API: `POST /api/v1/sse-tickets`

- 컨트롤러 위치는 `member/web/` (새 컨트롤러 `SseTicketController` 또는 `AuthController`에 추가 중 판단).
- 인증된 모든 역할(USER, ORGANIZER)이 호출할 수 있다. `SecurityConfig`의 `anyRequest().authenticated()`로 이미 막히는지 확인하고, 별도 permitAll을 추가하지 않는다.
- 응답 `201 Created`, 본문 `{"ticket": "...", "expiresAt": "2026-11-11T00:00:30Z"}`.
- 본문을 받지 않으므로 `Idempotency-Key`는 받지 않는다.

### 3. 테스트: `core/src/test/kotlin/io/github/ticketing/member/SseTicketIntegrationTests.kt`

`IntegrationTestSupport`를 상속한다. 다룰 경우:

- 인증된 회원이 발급하면 201과 티켓을 받고, Redis `sse:ticket:<ticket>` 값이 회원 ID이며 TTL이 설정돼 있다(`getExpire`가 0보다 크고 30초 이하).
- 토큰 없이 호출하면 401.
- 두 번 발급하면 서로 다른 티켓이 나온다.

## Acceptance Criteria

```bash
rg -q 'sse:ticket:' core/src/main/kotlin/io/github/ticketing/member
rg -q '/api/v1/sse-tickets' core/src/main/kotlin/io/github/ticketing/member
./scripts/agent/test/run-tests.sh :core:test --tests "*SseTicketIntegrationTests" --tests "*MemberAuthIntegrationTests" --tests "*ModularityTests"
./scripts/agent/test/check-summary.sh :core:ktlintCheck :core:detekt
```

## 검증 절차

1. `run-tests.sh :core:test --tests "*SseTicketIntegrationTests"`부터 통과시킨 뒤 위 AC 전체를 실행한다.

## 금지사항

- 티켓을 JWT나 서명 토큰으로 만들지 마라. 이유: 1회용 소비(`GETDEL`)로 재사용을 막는 것이 합의된 설계다(ADR 0020).
- 키를 `sse:ticket:<ticket>` 외의 형식으로 바꾸지 마라. 이유: Gateway(step 4)가 이 형식을 그대로 읽는다.
- `member` 밖의 모듈(booking 등)이나 루트 패키지에 이 기능을 두지 마라. 이유: 인증 수단 발급은 `member`의 책임이다.
- 티켓·토큰 값을 로그에 남기지 마라. 이유: `docs/rules/backend.md § 보안`.
- 기존 테스트를 깨뜨리지 마라.
