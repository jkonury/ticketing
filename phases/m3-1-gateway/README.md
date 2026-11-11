# Phase M3-1: gateway

- 기준: `docs/roadmap.md § M3. Gateway + 실시간 앱 > M3-1. gateway`
- 목표: 코어·실시간 앱 라우팅, 입장 토큰 검증, SSE 티켓 검증, rate limit을 Gateway에 구현한다. realtime(M3-2)은 아직 없으므로 Gateway 테스트는 Redis에 키를 직접 넣고 하위 서비스를 스텁으로 대체해 검증한다.
- M3 전체 완료 기준(대기열 → 입장 → 예매 확정, SSE 도달)은 M3-2·M3-3에서 확인한다. 이 phase는 각 step의 AC와 마지막 step의 문서 동기화로 끝난다.
- 각 step은 독립 세션에서 순서대로 실행한다. 앞 step의 AC가 통과해야 다음 step을 시작한다.

## 합의된 결정 (2026-11-11)

| 주제 | 결정 |
|---|---|
| JWT | Gateway도 코어와 같은 HS256 비밀(`JWT_SECRET`)로 서명을 검증한다. `Authorization: Bearer`가 있으면 모든 경로에서 검증하고, 실패하면 401 `UNAUTHORIZED`로 응답한다. 코어는 자체 검증을 그대로 유지한다(Gateway를 신뢰하지 않음) |
| 회원 전달 | 클라이언트가 보낸 `X-Member-Id`는 모든 요청에서 지운다. realtime 경로에만 검증된 회원 ID를 `X-Member-Id`로 붙인다 |
| 라우팅 | `/api/v1/queues/**`, `/api/v1/streams/**`는 realtime, 그 밖의 `/api/v1/**`는 core로 보낸다 |
| 입장 토큰 | 불투명 난수. 헤더 `X-Admission-Token`. Redis `queue:admission:<token>` Hash(`memberId`, `scheduleId`)에 TTL과 함께 저장된다(발급·TTL은 M3-2 realtime, 기본 10분). TTL 동안 여러 번 쓸 수 있다 |
| 입장 토큰 적용 범위 | `POST /api/v1/bookings`에만 적용한다. Gateway가 본문을 캐시해 `scheduleId`를 읽고 토큰의 회원·회차와 대조한다. 결제·취소·조회에는 적용하지 않는다 |
| SSE 티켓 | 코어 `member`의 `POST /api/v1/sse-tickets`(JWT 인증)가 발급한다. Redis `sse:ticket:<ticket>` → 회원 ID, TTL 30초. Gateway가 `?ticket=`을 받아 `GETDEL`로 한 번만 소비한다. 재연결할 때마다 새 티켓이 필요하다. M4의 BFF는 이 API를 호출만 한다 |
| Rate limit | Spring Cloud Gateway 내장 `RedisRateLimiter`(토큰 버킷). 키는 검증된 회원 ID, 없으면 클라이언트 IP. 기본 한도와 예매 생성 한도를 설정으로 둔다 |
| 오류 코드 | ProblemDetail + `code`. 입장 토큰 없음 403 `ADMISSION_TOKEN_REQUIRED`, 무효·만료·불일치 403 `ADMISSION_TOKEN_INVALID`, SSE 티켓 401 `SSE_TICKET_INVALID`, rate limit 429 `RATE_LIMITED`, JWT 401 `UNAUTHORIZED` |
| CORS | 브라우저가 직접 붙는 `/api/v1/streams/**`에만 `web` origin(설정값, 기본 `http://localhost:3000`)을 허용한다 |

## Step

| N | 이름 | 대상 | model / reasoning_effort |
|---|---|---|---|
| 0 | [gateway-decisions-docs](./step0-gateway-decisions-docs.md) | docs | haiku / medium |
| 1 | [sse-ticket-issue-api](./step1-sse-ticket-issue-api.md) | core `member` | 기본 / medium |
| 2 | [gateway-routing-auth](./step2-gateway-routing-auth.md) | gateway (빌드·라우팅·JWT·테스트 기반) | 기본 / high |
| 3 | [admission-token-filter](./step3-admission-token-filter.md) | gateway (입장 토큰) | 기본 / high |
| 4 | [sse-ticket-filter](./step4-sse-ticket-filter.md) | gateway (SSE 티켓, CORS) | 기본 / medium |
| 5 | [rate-limit](./step5-rate-limit.md) | gateway (rate limit) | 기본 / medium |
| 6 | [docs-finalize](./step6-docs-finalize.md) | docs | haiku / medium |

## Redis 키 (이 phase에서 정하는 계약)

| 키 | 자료구조 | 쓰는 쪽 | 읽는 쪽 |
|---|---|---|---|
| `queue:admission:<token>` | Hash `memberId`, `scheduleId`, TTL | realtime (M3-2) | gateway (`HGETALL`) |
| `sse:ticket:<ticket>` | String(회원 ID), TTL 30초 | core `member` | gateway (`GETDEL`) |
| `request_rate_limiter.{<key>}.*` | SCG `RedisRateLimiter` 내부 키(형식은 라이브러리가 정함) | gateway | gateway |
