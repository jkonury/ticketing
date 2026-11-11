# Step 0: gateway-decisions-docs

- model: haiku / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `phases/m3-1-gateway/README.md` (합의된 결정, Redis 키 표)
- `docs/architecture.md § 3. 시스템 구성`, `§ 5. 데이터 > Redis 용도`, `§ 6.1 대기열과 입장`, `§ 7. 인증·인가`
- `docs/adr/README.md`, `docs/templates/adr.md`
- `docs/adr/0008-queue-in-realtime-app.md`, `docs/adr/0014-sse-bypasses-bff.md` (링크 대상, 수정 금지)

큰 문서는 통째로 `Read`하지 말고 `rg -n '^#+ ' docs/architecture.md`로 줄 번호를 찾아 `Read offset/limit`으로 해당 절만 읽는다.

## 작업

M3-1 구현 전에 합의된 Gateway 설계 결정을 문서에 반영한다. 코드는 바꾸지 않는다.

### 1. ADR 0020 작성: `docs/adr/0020-gateway-verification-contracts.md`

`docs/templates/adr.md` 형식(상태 / 맥락 / 검토한 대안 / 결정 / 결과)을 따른다. 상태는 `수락`, 일자는 2026-11-11. 0008·0014를 구체화하는 결정이며 대체하지 않는다. 담을 결정 네 가지:

1. **Gateway도 JWT 서명을 검증한다.** 코어와 같은 HS256 비밀을 쓴다. `Authorization: Bearer`가 있으면 모든 경로에서 검증하고 실패하면 401로 응답한다. 클라이언트가 보낸 `X-Member-Id`는 지우고, realtime 경로에만 검증된 회원 ID를 `X-Member-Id`로 붙인다. 코어는 자체 검증을 유지한다.
   - 대안: 서명 검증 없이 `sub`만 디코드. 기각 이유: realtime이 위조된 회원 ID를 그대로 믿게 되고, rate limit 키도 위조로 우회된다.
   - 대안: JWT를 보지 않고 입장 토큰을 회원에 묶지 않음. 기각 이유: 토큰을 넘겨받은 다른 사용자가 대기열을 건너뛴다.
   - 결과: 비밀을 공유하는 배포 단위가 둘이 된다. 비대칭키(JWKS) 전환은 MSA 단계에서 검토한다(7절의 기존 메모와 같다).
2. **입장 토큰은 회원과 회차에 묶고 예매 생성에만 적용한다.** 불투명 난수를 `X-Admission-Token` 헤더로 받는다. Redis `queue:admission:<token>` Hash(`memberId`, `scheduleId`)에 TTL과 함께 둔다(발급은 realtime). TTL 안에서는 여러 번 쓸 수 있다. `POST /api/v1/bookings`에서 Gateway가 본문을 캐시해 `scheduleId`를 읽고 대조한다.
   - 대안: 회원만 대조. 기각 이유: A 회차로 입장한 사용자가 B 회차를 예매할 수 있다.
   - 대안: 코어 API를 `/api/v1/schedules/{id}/bookings`로 바꿔 경로로 대조. 기각 이유: Gateway 사정으로 코어 API 모양을 바꾸게 된다.
   - 결과: Gateway가 이 경로 하나의 본문을 읽는다. 본문 크기에 상한을 둔다.
3. **SSE 티켓은 코어 `member`가 발급하는 1회용 키다.** `POST /api/v1/sse-tickets`(JWT 인증)가 난수를 `sse:ticket:<ticket>` → 회원 ID(TTL 30초)로 저장한다. Gateway가 `?ticket=`을 `GETDEL`로 소비한 뒤 하위 요청에서 `ticket` 쿼리를 지운다.
   - 대안: BFF가 Redis에 직접 기록. 기각 이유: M4 이전에는 발급 수단이 없고, Next.js에 Redis 의존과 키 형식 지식이 생긴다.
   - 대안: HMAC 서명 무상태 티켓. 기각 이유: TTL 안에서 재사용할 수 있어 URL(로그, 기록)로 새면 그대로 쓰인다.
   - 결과: 재연결할 때마다 새 티켓이 필요하다(0014의 결과와 같다).
4. **rate limit은 Spring Cloud Gateway 내장 `RedisRateLimiter`를 쓴다.** 키는 검증된 회원 ID, 없으면 클라이언트 IP다. 기본 한도와 예매 생성 한도를 설정값으로 둔다.
   - 대안: 고정 윈도 Lua를 직접 구현. 기각 이유: 내장 토큰 버킷으로 충분하고 코드만 늘어난다.

### 2. `docs/adr/README.md` 목록에 0020 행 추가

### 3. `docs/architecture.md` 수정

- `§ 5 > Redis 용도`: "입장 토큰" 행에 키 형식 `queue:admission:<token>`(Hash `memberId`, `scheduleId`)을 적는다. "SSE 티켓" 행(`sse:ticket:<ticket>`, String, 코어 `member` 발급·Gateway 소비)과 "rate limit" 행(SCG `RedisRateLimiter`, 소유 gateway)을 추가한다.
- `§ 6.1 대기열과 입장`: 4번 문장 뒤에 Gateway 검증 규칙을 목록으로 추가하고 ADR 0020을 링크한다.
  - 라우팅: `/api/v1/queues/**`, `/api/v1/streams/**` → realtime, 그 밖의 `/api/v1/**` → core
  - 입장 토큰: `POST /api/v1/bookings`에만 적용, 회원·회차 대조, 없으면 403 `ADMISSION_TOKEN_REQUIRED`, 무효·만료·불일치는 403 `ADMISSION_TOKEN_INVALID`(클라이언트는 이 두 코드로 대기열에 돌려보낸다)
  - SSE 티켓: `/api/v1/streams/**`에서 `?ticket=` 1회 소비, 실패 시 401 `SSE_TICKET_INVALID`
  - rate limit: 초과 시 429 `RATE_LIMITED`
- `§ 7. 인증·인가`: "구현 세부" 아래에 Gateway의 JWT 검증과 `X-Member-Id` 처리, SSE 티켓 발급 API(`POST /api/v1/sse-tickets`, TTL 30초, 1회용)를 한 줄씩 추가한다.

## Acceptance Criteria

```bash
test -f docs/adr/0020-gateway-verification-contracts.md
rg -q '0020-gateway-verification-contracts' docs/adr/README.md
rg -q '0020-gateway-verification-contracts' docs/architecture.md
rg -q 'queue:admission:' docs/architecture.md
rg -q 'sse:ticket:' docs/architecture.md
rg -q 'ADMISSION_TOKEN_REQUIRED' docs/architecture.md
rg -q '/api/v1/sse-tickets' docs/architecture.md
./scripts/agent/docs/check-docs.sh
```

## 검증 절차

1. 위 AC를 실행하고, `check-docs.sh`가 실패하면 링크·앵커를 고친다.

## 금지사항

- 기존 ADR(0001~0019)의 결정 내용을 고치지 마라. 이유: 수락된 결정은 새 ADR로만 바꾼다. 0020은 0008·0014를 구체화할 뿐 대체하지 않는다.
- 코드와 설정 파일을 건드리지 마라. 이유: 문서 전용 step이다.
- `docs/roadmap.md` 체크박스를 체크하지 마라. 이유: 구현이 끝나지 않았다.
