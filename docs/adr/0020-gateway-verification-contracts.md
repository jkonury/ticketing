# 0020. Gateway 검증 계약 구체화

- 상태: 수락
- 일자: 2026-11-11
- 관련: [설계](../architecture.md), [ADR 0008](0008-queue-in-realtime-app.md), [ADR 0014](0014-sse-bypasses-bff.md)

## 맥락

ADR 0008은 실시간 앱이 대기열을 소유하고 Gateway가 입장 토큰을 검증한다고 정했고, ADR 0014는 브라우저가 단기 티켓으로 Gateway에 직접 SSE 연결한다고 정했다. 구현에 앞서 JWT 전달, 입장 토큰의 적용 범위, 티켓 소비, rate limit의 구체 계약을 정해야 한다. Gateway는 코어 앞에서 요청을 검증하지만, 코어가 Gateway를 신뢰하는 구조로 만들 수는 없다.

## 결정

1. Gateway도 코어와 같은 HS256 비밀로 JWT 서명을 검증한다. `Authorization: Bearer`가 있으면 모든 경로에서 검증하고 실패 시 401로 응답한다. Gateway는 클라이언트가 보낸 `X-Member-Id`를 지우고 realtime 경로에만 검증된 회원 ID를 전달한다. 코어는 자체 검증을 유지한다.
2. 입장 토큰은 회원과 회차에 묶고 예매 생성에만 적용한다. 불투명 난수를 `X-Admission-Token` 헤더로 받고 Redis `queue:admission:<token>` Hash의 `memberId`, `scheduleId` 필드와 TTL을 확인한다. TTL 동안 여러 번 사용할 수 있다. `POST /api/v1/bookings`에서 Gateway가 본문을 캐시해 회차를 대조한다.
3. SSE 티켓은 코어 `member`가 발급하는 1회용 키다. JWT 인증된 `POST /api/v1/sse-tickets`가 난수를 Redis `sse:ticket:<ticket>`에 회원 ID로 저장하고 TTL은 30초로 둔다. Gateway는 `?ticket=`을 `GETDEL`로 소비하고 하위 요청에서 `ticket` 쿼리를 제거한다. 소비 전에 티켓을 조회해 회원 키로 rate limit을 먼저 적용하므로, 한도 초과(429) 요청은 티켓을 소비하지 않는다.
4. rate limit은 Spring Cloud Gateway 내장 `RedisRateLimiter`를 사용한다. 키는 검증된 회원 ID, 없으면 클라이언트 IP로 정하고 기본 한도와 예매 생성 한도를 설정값으로 둔다.

비회원 IP의 신뢰 경계는 [ADR 0046](0046-attested-client-ip.md)에서 정한다.

## 검토한 대안

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| JWT 서명을 확인하지 않고 `sub`만 디코드 | realtime이 위조된 회원 ID를 믿을 수 있고 rate limit 키를 위조해 우회할 수 있다. |
| JWT를 보지 않고 입장 토큰을 회원에 묶지 않음 | 토큰을 넘겨받은 다른 사용자가 대기열을 건너뛸 수 있다. |
| 입장 토큰에서 회원만 대조 | A 회차로 입장한 사용자가 B 회차를 예매할 수 있다. |
| 코어 API를 `/api/v1/schedules/{id}/bookings`로 변경 | Gateway 사정으로 코어 API 모양을 바꾸게 된다. |
| BFF가 Redis에 SSE 티켓을 직접 기록 | M4 이전에는 발급 수단이 없고 Next.js에 Redis 의존과 키 형식 지식이 생긴다. |
| HMAC 서명 무상태 SSE 티켓 | TTL 동안 재사용할 수 있어 URL이나 로그로 새면 다시 쓸 수 있다. |
| 직접 구현한 고정 윈도 Lua rate limit | 내장 토큰 버킷으로 충분하며 직접 구현은 코드를 늘린다. |

## 결과

- 얻는 것: 실시간 앱과 Gateway가 JWT 서명을 확인하고, 예매 입장과 SSE 연결을 회원·회차·1회 사용 계약으로 검증한다. 공유 Redis rate limit을 이용한다.
- 감수하는 것: JWT 비밀을 공유하는 배포 단위가 둘이 된다. Gateway가 예매 생성 경로의 본문을 읽으므로 본문 크기 상한을 둬야 한다. SSE 재연결마다 새 티켓이 필요하다.
- 바뀌는 문서·지침: `docs/architecture.md`, `docs/adr/README.md`.

## 검증

Gateway 통합 테스트에서 JWT 검증과 회원 헤더 처리, 입장 토큰의 회원·회차 대조, SSE 티켓 1회 소비, rate limit 응답을 확인한다. 설계 문서는 `./scripts/agent/docs/check-docs.sh`로 링크와 경로를 검사한다.

## 변경 이력

- 2026-11-11: 결정 수락
