# Frontend 지침 (Next.js BFF)

Next.js(App Router) + TypeScript. `web`은 화면이면서 **BFF**다. 책임은 두 가지: 인증/토큰 관리, 여러 API의 데이터 조합. 설계 근거는 `docs/architecture.md` 7~8장.

Next.js는 버전 간 변화가 크다. 캐싱, 쿠키, Route Handler, 미들웨어 관련 API는 설치된 버전의 공식 문서로 확인하고 작성한다.

## 디렉터리

```
web/src
├── app/                 라우트, 레이아웃, Route Handler
│   └── api/             브라우저가 호출하는 BFF 엔드포인트
├── server/              서버 전용 코드 (import 'server-only')
│   ├── auth/            세션, 토큰 보관·갱신
│   ├── api/             Gateway 호출 클라이언트 (도메인별)
│   └── aggregate/       화면 단위 조합 함수
├── features/            기능별 UI와 클라이언트 로직 (booking, queue, ...)
├── components/          범용 UI 컴포넌트
└── lib/                 서버·클라이언트 공용 순수 유틸, 타입
```

- `server/` 아래 모든 파일은 `import 'server-only'`로 시작한다. 클라이언트 컴포넌트에서 import하면 빌드가 실패해야 한다.

## 토큰과 세션 (최우선 규칙)

- **JWT(access/refresh)는 브라우저에 절대 전달하지 않는다.** 응답 본문, 클라이언트 컴포넌트 props, `NEXT_PUBLIC_*` 환경 변수, 브라우저 저장소 어디에도 두지 않는다.
- 브라우저에는 세션 쿠키만 내려간다: `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`.
- 토큰은 서버 측 세션에 둔다. 세션을 다루는 코드는 `server/auth/`에만 둔다.
- access 토큰 만료 시 갱신은 `server/api/`의 공통 클라이언트가 서버에서 처리한다. 동시에 여러 요청이 만료를 만나도 갱신은 한 번만 일어나게 한다.
- 갱신 실패 시 세션을 파기하고 로그인으로 보낸다.
- 상태를 바꾸는 요청(Route Handler의 POST 등, Server Action)은 CSRF 방어를 적용한다 (Origin 검증).
- 화면에서 메뉴를 숨기는 것은 편의일 뿐이다. 인가는 백엔드가 강제하며, BFF는 백엔드의 401/403을 그대로 반영한다.

## 백엔드 호출

- 브라우저는 백엔드(Gateway)를 직접 호출하지 않는다. **유일한 예외는 SSE**다.
- Gateway 호출은 `server/api/`의 클라이언트로만 한다. 컴포넌트나 Route Handler에서 `fetch`로 Gateway URL을 직접 부르지 않는다.
- Gateway 주소 등 서버 설정은 `NEXT_PUBLIC_` 접두어 없는 환경 변수로 둔다.
- 백엔드 응답은 경계에서 스키마(zod)로 검증하고 타입을 얻는다. `any`와 타입 단언(`as`)으로 넘기지 않는다.
- 백엔드 오류는 `ProblemDetail`의 `code`로 분기한다. 메시지 문자열로 분기하지 않는다.
- 사용자별 데이터는 캐시하지 않는다(`no-store`). 공연 목록 같은 공개 데이터만 재검증 주기를 두고 캐시한다.
- 결제·예매 생성 요청에는 `Idempotency-Key`를 붙이고, 재시도 시 같은 키를 쓴다.

## 조합 (Aggregation)

- 화면 하나에 필요한 데이터 조합은 `server/aggregate/`의 함수 하나로 만든다 (예: `getPerformanceDetail`, `getMyPage`, `getOrganizerDashboard`).
- 독립적인 하위 호출은 병렬로 보낸다. 순차 `await`로 폭포를 만들지 않는다.
- **부분 실패를 허용한다.** 핵심 데이터(공연 정보)가 실패하면 전체 실패, 부가 데이터(잔여 좌석, 내 예매 여부)가 실패하면 해당 부분만 비운 채 응답한다. 어느 쪽이 핵심인지 조합 함수에 명시한다.
- 하위 호출마다 타임아웃을 둔다.
- 조합 결과는 화면에 필요한 형태로 가공한 타입이다. 백엔드 응답을 그대로 이어 붙여 내보내지 않는다.

## SSE

- SSE는 BFF를 거치지 않고 브라우저가 Gateway에 직접 연결한다.
- 연결 전 BFF의 엔드포인트에서 **단기 SSE 티켓**을 받아 사용한다. JWT를 URL이나 헤더에 싣지 않는다.
- 티켓은 수명이 짧으므로 재연결할 때마다 새로 받는다.
- SSE 연결은 `features/` 안의 훅 하나로 감싼다. 언마운트 시 반드시 연결을 닫는다.
- 재연결은 지수 백오프로 하고, `Last-Event-ID`로 이어 받는다.
- 좌석 화면은 접속 시 스냅샷을 받고 이후 변경분을 적용한다. 화면의 좌석 상태는 표시용이며, 선점 성공 여부는 서버 응답으로만 판단한다.

## 컴포넌트

- 기본은 Server Component. `'use client'`는 상호작용이나 브라우저 API가 필요한 최소 단위에만 붙인다.
- 데이터 조회는 Server Component에서 조합 함수를 호출해 한다. 클라이언트에서 `useEffect`로 초기 데이터를 가져오지 않는다.
- 변경(예매, 취소, 로그인)은 Server Action 또는 Route Handler로 한다.
- 라우트마다 `loading`과 `error` 경계를 둔다.
- 서버 상태를 전역 클라이언트 스토어에 복사하지 않는다. 클라이언트 상태는 UI 상태(선택한 좌석, 모달)로 한정한다.

## 예매 화면 규칙

- 선점 남은 시간은 서버가 준 만료 시각 기준으로 표시한다. 클라이언트에서 5분을 따로 세지 않는다.
- 결제·예매 버튼은 요청 중 비활성화하고, 중복 제출을 막는다.
- 오류 코드별 안내를 구분한다: 좌석 선점 실패, 선점 만료, 입장 토큰 만료(대기열로 복귀), 결제 실패.

## TypeScript와 스타일

- 화면 구성과 시각 규칙은 [UI 가이드](../ui-guide.md)를 따른다.
- `strict` 모드. `any` 금지, `as` 단언은 사유 주석과 함께 최소화한다.
- 컴포넌트는 함수 선언과 named export. 기본 export는 Next.js가 요구하는 파일(`page`, `layout` 등)에만 쓴다.
- 파일명은 kebab-case, 컴포넌트 이름은 PascalCase.
- ESLint와 Prettier 설정을 따르고 규칙을 끄는 주석을 임의로 넣지 않는다.
- 시맨틱 마크업과 키보드 조작을 기본으로 한다. 좌석 버튼에는 좌석 번호와 상태를 읽을 수 있는 레이블을 준다.

## 테스트

- 조합 함수와 토큰 갱신 로직: 단위 테스트. 하위 API를 가짜로 두고 부분 실패, 타임아웃, 동시 갱신을 검증한다.
- 컴포넌트: 사용자 관점의 동작 위주로 테스트한다.
- E2E(Playwright): 로그인 → 대기열 → 좌석 선택 → 결제 → 예매 완료.
- 토큰 비노출 검증: E2E에서 브라우저 저장소와 네트워크 응답에 JWT가 없음을 확인한다 (M4 완료 기준).
- 실행: `./scripts/agent/test/web-check.sh [--build]`
- 실행: `./scripts/e2e/run.sh --web`
