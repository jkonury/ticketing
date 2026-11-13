# Step 9: web-e2e

- model: 기본 / reasoning_effort: high

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `scripts/e2e/run.sh` (수정 대상. 기동 순서, 포트 검사, 헬스 대기, 로그, `--keep`·`--tests`)
- `e2e/src/test/kotlin/io/github/ticketing/e2e/support/E2eClient.kt` (API로 시드 데이터를 만드는 순서 참고)
- `docs/rules/frontend.md § 테스트`
- `web/src/features/booking/booking-flow.tsx`, `web/src/app/` (step 5~8 결과: 화면 경로와 접근 가능한 레이블)

## 작업

M4 완료 기준을 브라우저 E2E로 검증한다: 전체 예매 시나리오 동작, 브라우저 저장소와 네트워크 응답 어디에도 JWT 없음.

### 1. Playwright 설정: `web/playwright.config.ts`, `web/e2e/`

- 개발 의존성 `@playwright/test`. 브라우저는 chromium만(`pnpm exec playwright install chromium`).
- `baseURL` = `E2E_WEB_URL`(기본 `http://localhost:3000`). `webServer`는 쓰지 않는다(`run.sh`가 띄운다). trace는 실패 시만.
- `package.json` script `e2e`(`playwright test`). Vitest가 `web/e2e/`를 집지 않도록 제외한다. `web-check.sh`에는 넣지 않는다.

### 2. 시드: `web/e2e/support/seed.ts`

Playwright `request` 컨텍스트로 Gateway(`E2E_GATEWAY_URL`, 기본 `http://localhost:8000`)를 직접 호출한다(테스트 준비용이며 브라우저 페이지가 아니다). 주최자 가입·로그인 → 공연장(좌석 8석 정도) → 공연 → 회차(내일) 순. 이메일은 실행마다 고유하게.

### 3. 시나리오: `web/e2e/booking-flow.spec.ts`

1. 구매자 페이지(`page`)와 관람자 페이지(별도 `browser.newContext()`)를 준비한다. 둘 다 UI로 가입·로그인한다.
2. 관람자: 공연 상세 → "예매하기" → 대기열 통과 후 좌석 화면 대기.
3. 구매자: 공연 목록 → 상세(잔여 좌석 표시 확인) → "예매하기" → 대기열 순번 또는 즉시 입장 → 좌석 2개 선택(접근 가능한 이름으로 찾는다) → 예매 → 결제 → 완료 화면 확인.
4. 관람자 화면에서 그 두 좌석이 선택 불가 상태로 바뀌는지 확인(SSE 반영).
5. 구매자 마이페이지에 예매가 `확정` 상태와 결제 상태로 보이는지 확인. 공연 상세에 "예매 내역 있음" 확인.
6. 주최자 UI 로그인 → 대시보드에서 해당 회차 판매 좌석 2 확인.

### 4. JWT 비노출 검사: `web/e2e/support/jwt-guard.ts`

모든 페이지(컨텍스트)에 붙여 시나리오 내내 수집하고, 시나리오 끝에 단언한다.

- JWT 패턴: `eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+`.
- 네트워크: `context.on('response')`로 모든 응답의 헤더 전체와 본문(텍스트 계열, SSE 포함 가능한 범위)을 검사한다. SSE 응답은 본문을 끝까지 읽을 수 없으면 `page.on('websocket')`이 아닌 `EventSource` 수신 data를 `page.exposeFunction` 또는 `addInitScript`로 가로채 검사한다.
- 저장소: 시나리오 끝에 `context.cookies()`의 값, `localStorage`·`sessionStorage` 전체, `indexedDB.databases()` 목록을 검사한다.
- 세션 쿠키는 `HttpOnly`이고 값이 JWT 형식이 아님을 단언한다.
- 검사기가 실제로 잡는지 확인하는 자체 테스트(`jwt-guard.spec.ts`)를 둔다: `page.route`로 JWT를 담은 가짜 응답을 주면 위반으로 보고된다.

### 5. `scripts/e2e/run.sh --web`

- `--web` 옵션: 기존 백엔드 기동·헬스 대기 뒤 `web`을 `pnpm install --frozen-lockfile`(필요 시) → `pnpm build` → `pnpm start`(포트 3000, 환경 변수 `GATEWAY_URL`·`GATEWAY_PUBLIC_URL`·`REDIS_URL`·`WEB_ORIGIN`)로 띄우고 `http://localhost:3000`이 응답할 때까지 기다린 뒤 `pnpm e2e`를 실행한다. 로그는 `build/e2e/logs/web.log`, Playwright 출력은 `build/e2e/logs/web-e2e.log`.
- `--web`이면 Kotlin `:e2e:e2eTest`는 건너뛴다(둘 다 원하면 각각 실행). 포트 검사에 3000을 더한다(`--web`일 때만). 종료 시 web 프로세스도 정리하고 `--keep`이면 PID를 출력한다.
- `--tests` 패턴은 `--web`일 때 Playwright `-g`로 전달한다.

## Acceptance Criteria

```bash
test -f web/playwright.config.ts && test -f web/e2e/booking-flow.spec.ts && test -f web/e2e/support/jwt-guard.ts
rg -q -- '--web' scripts/e2e/run.sh
./scripts/agent/test/web-check.sh
./scripts/e2e/run.sh --web
```

## 검증 절차

1. `jwt-guard.spec.ts`가 위반을 잡는지부터 확인한다.
2. `./scripts/e2e/run.sh --web`을 실행한다. 실패하면 `build/e2e/logs/web-e2e.log`와 서비스 로그에서 `rg -n -m 20`으로 원인을 좁힌다. 앱 버그면 해당 web 코드를 고치고, 백엔드 버그로 보이면 고치지 말고 근거와 함께 보고한다.
3. 기존 Kotlin E2E가 깨지지 않았는지 `./scripts/e2e/run.sh`(옵션 없이)도 한 번 실행한다.

## 금지사항

- JWT 검사 범위를 줄이거나 특정 응답을 예외로 빼서 통과시키지 마라. 이유: M4 완료 기준 그 자체다. 위반이 나오면 원인을 고친다.
- 시나리오를 API 호출로 대체하지 마라(시드 준비 제외). 이유: 완료 기준은 "브라우저에서" 동작이다.
- `waitForTimeout` 고정 대기로 SSE 반영을 기다리지 마라. 이유: 불안정하다. `expect(...).toHave...`의 자동 재시도를 쓴다.
- 실패하는 테스트를 `skip`·`fixme`로 바꾸지 마라. 이유: AGENTS.md 8절.
- 백엔드(core·realtime·gateway) 코드를 고치지 마라. 이유: 이 step은 검증 단계다. 백엔드 문제는 보고한다.
