# Step 0: bff-decisions-docs

- model: haiku / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `phases/m4-bff/README.md` (합의된 결정, 추가 코어 API 표, Redis 키 표)
- `docs/adr/0021-realtime-queue-and-sse-contracts.md` (머리말 형식 참고, 수정 금지)
- `docs/adr/README.md § 목록`
- `docs/architecture/integrations.md § 7. 인증·인가`, `§ 8. BFF (Next.js)`
- `docs/architecture/redis.md § Redis 용도`
- `docs/architecture/flows.md § 6.1` (좌석 SSE `Last-Event-ID` 줄. `rg -n 'Last-Event-ID' docs/architecture/flows.md`로 찾는다)

## 작업

M4 구현 전에 합의된 BFF 설계 결정을 문서에 반영한다. 코드는 바꾸지 않는다.

### 1. ADR 0022 작성: `docs/adr/0022-bff-session-and-aggregation-apis.md`

ADR 0021의 머리말 형식(상태 `수락`, 작성일·합의일 2026-11-12, 결정 주체, 대체, 대체됨·일부 변경, 관련)과 절 구성(배경 / 검토한 대안 / 결정 / 결과)을 따른다. 관련에는 ADR 0013, 0014, 0020, 0021과 설계 문서를 링크한다. 0013·0014를 구체화하며 대체하지 않는다. 결정은 번호 목록으로 나눈다:

1. **BFF 세션은 Redis에 둔다.** `bff:session:<sessionId>`에 토큰과 만료 시각을 두고 쿠키에는 불투명 세션 ID만 둔다.
   - 대안: 프로세스 메모리. 기각 이유: BFF 재시작 시 로그아웃되고 여러 인스턴스를 띄울 수 없다(0013의 남은 과제).
   - 대안: 암호화 쿠키에 토큰 저장. 기각 이유: 암호문이라도 토큰이 브라우저 저장소에 실려 M4 완료 기준(브라우저 어디에도 JWT 없음)의 해석 여지가 생긴다.
2. **토큰 갱신은 세션당 한 번만 일어나게 한다.** 프로세스 안 single-flight와 Redis `bff:refresh-lock:<sessionId>`(`SET NX PX`)를 함께 쓴다. 이유: refresh 토큰은 로테이션되고 재사용이 감지되면 회원의 모든 refresh 토큰이 폐기되므로, 동시 갱신이 겹치면 사용자가 로그아웃된다. 갱신 실패 시 세션을 파기한다.
3. **로그아웃은 코어의 refresh 토큰 폐기 API를 호출한다.** `POST /api/v1/auth/tokens/revoke`(공개, 멱등 204).
   - 대안: BFF 세션만 삭제. 기각 이유: refresh 토큰이 수명(14일)까지 유효하게 남는다.
4. **조합에 필요한 조회 API를 소유 모듈에 추가한다.** README "추가 코어 API" 표의 다섯 항목을 옮겨 적는다. 각 모듈은 자기 스키마만 조회한다.
   - 대안: 기존 API만으로 BFF가 근사(제목 매칭, 예매 상태로 결제 상태 대체, 정산으로 판매 현황 대체). 기각 이유: 조합과 부분 실패 처리의 의미가 없어지고, 제목 매칭은 틀릴 수 있다.
5. **좌석 SSE는 `lastEventId` 쿼리로도 이어 받는다.** SSE 티켓이 1회용이어서 브라우저 `EventSource`의 자동 재연결(같은 URL 재사용)을 쓸 수 없고, 새 티켓으로 직접 다시 연결하면 `Last-Event-ID` 헤더를 실을 수 없다. realtime 좌석 스트림은 헤더가 없으면 `?lastEventId=`를 쓴다(헤더 우선).
   - 대안: Gateway CORS에 `Last-Event-ID` 요청 헤더를 허용하고 fetch 기반 SSE 클라이언트를 쓴다. 기각 이유: 브라우저 쪽에 스트림 파서와 재연결을 직접 구현해야 하고 preflight가 생긴다.
6. **입장 토큰은 브라우저가 대기열 SSE로 받아 BFF에 넘긴다.** 불투명 난수이며 JWT가 아니다. BFF가 `X-Admission-Token`으로 전달한다.

결과 절에는 Redis 의존이 BFF에 생기는 점, 코어 API가 화면 요구로 늘어나는 점, `lastEventId`가 URL에 남는 점(비밀 값이 아니므로 허용)을 적는다. 검증 절에는 step 5의 동시 갱신 단위 테스트와 step 9의 Playwright JWT 비노출 검사를 적는다(파일명은 아직 없으므로 "web 단위 테스트", "Playwright E2E"로 쓴다).

### 2. `docs/adr/README.md § 목록`에 0022 행 추가

기존 표 형식(`| [0022](...) | 제목 | 수락 |`)을 따른다.

### 3. `docs/architecture/integrations.md`

- `§ 7`: 로그아웃 시 BFF가 `POST /api/v1/auth/tokens/revoke`를 호출한다는 항목을 추가한다.
- `§ 8`: 세션 저장(Redis `bff:session:<sessionId>`, 쿠키 속성 `HttpOnly`·`SameSite=Lax`·`Path=/`·운영 `Secure`), 갱신 규칙(만료 30초 전 또는 401, 세션당 1회, 실패 시 세션 파기), CSRF(Origin 검사), 조합별 핵심/부가 데이터 구분(README 표 그대로), 조합에 쓰는 코어 API 목록을 추가한다. ADR 0022를 링크한다.

### 4. `docs/architecture/redis.md § Redis 용도`

표에 두 행을 추가한다: `bff:session:<sessionId>` String(JSON), TTL 14일 / `bff:refresh-lock:<sessionId>` `SET NX PX`. 소유는 `web` BFF.

### 5. `docs/architecture/flows.md`

좌석 SSE `Last-Event-ID` 줄에 "헤더가 없으면 `lastEventId` 쿼리를 쓴다(브라우저 재연결용, ADR 0022)"를 덧붙인다.

## Acceptance Criteria

```bash
test -f docs/adr/0022-bff-session-and-aggregation-apis.md
rg -q '0022' docs/adr/README.md
rg -q 'bff:session' docs/architecture/redis.md
rg -q 'tokens/revoke' docs/architecture/integrations.md
rg -q 'lastEventId' docs/architecture/flows.md
./scripts/agent/docs/check-docs.sh
```

## 검증 절차

1. 위 AC를 실행하고, `check-docs.sh`가 실패하면 링크·앵커·백틱 경로를 고친다. 아직 존재하지 않는 코드 경로를 백틱으로 쓰면 `check-doc-refs.sh`가 실패하므로, 미래 경로는 백틱 없이 쓰거나 쓰지 않는다.

## 금지사항

- 기존 ADR(0001~0021)의 본문을 고치지 마라. 이유: 합의된 결정은 새 ADR로만 바꾼다(`docs/templates/adr.md` 원칙 3).
- `docs/roadmap.md`의 체크박스를 건드리지 마라. 이유: 구현 전이다.
- 코드와 설정 파일을 건드리지 마라. 이유: 문서 전용 step이다.
