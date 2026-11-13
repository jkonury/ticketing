# Step 4: web-scaffold

- model: 기본 / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `docs/rules/frontend.md` (전체. 짧다)
- `phases/m4-bff/README.md § 합의된 결정` (버전·도구 행)
- `scripts/agent/test/run-summary.sh` (사용법 주석), `scripts/agent/hook/stop_quality.sh`, `.githooks/pre-commit`
- `AGENTS.md § 7. 테스트 실행` (수정 대상)

## 작업

`web/`에 Next.js 프로젝트 뼈대와 검사 도구를 만든다. 화면·인증 로직은 만들지 않는다.

### 1. 프로젝트 생성

- `create-next-app@16.4.6`을 비대화식으로 실행한다(App Router, TypeScript, `src/` 디렉터리, Tailwind, ESLint, import alias `@/*`, pnpm). 사용 가능한 플래그는 `pnpm dlx create-next-app@16.4.6 --help`로 먼저 확인한다.
- 패키지 관리자는 pnpm이다. `package.json`에 `"packageManager": "pnpm@12.18.0"`을 두고 `web/pnpm-lock.yaml`을 커밋 대상으로 만든다. `package-lock.json`·`yarn.lock`은 만들지 않는다(생겼으면 지운다).
- pnpm은 의존성의 설치 스크립트를 기본으로 막는다. 빌드에 필요한 패키지(예: `sharp`, `unrs-resolver`, `@tailwindcss/oxide`)만 pnpm 설정(`web/pnpm-workspace.yaml`의 `onlyBuiltDependencies` 등, 설치된 pnpm 버전 문서로 키 이름 확인)으로 허용한다. 대화형 `pnpm approve-builds`는 쓰지 않는다.
- `next` 버전을 `16.4.6`으로 고정한다(`^` 없이). 기본 예제 페이지·이미지는 지우고 `app/page.tsx`는 제목만 있는 자리표시로 둔다.
- 추가 의존성: `zod`, `server-only`. 개발 의존성: `prettier`, `eslint-config-prettier`, `vitest`, `@vitejs/plugin-react`, `jsdom`, `@testing-library/react`, `@testing-library/user-event`, `@testing-library/jest-dom`.
- `tsconfig.json` `strict: true`. ESLint에 `@typescript-eslint/no-explicit-any: error`를 둔다. Prettier와 충돌하는 규칙은 `eslint-config-prettier`로 끈다.
- `package.json` scripts: `dev`, `build`, `start`, `lint`, `typecheck`(`tsc --noEmit`), `test`(`vitest run`), `format`, `format:check`.
- Vitest: `vitest.config.ts`(jsdom, `@/*` alias, setup에서 jest-dom). `server-only`를 import하는 모듈을 테스트할 수 있도록 테스트 환경에서 `server-only`를 빈 모듈로 alias한다.
- Next 16의 설정·`proxy.ts`·캐싱 API는 설치된 버전 문서로 확인한다(`node_modules/next/dist/docs/`가 있으면 그것, 없으면 nextjs.org 공식 문서).

### 2. 디렉터리와 공용 코드

`docs/rules/frontend.md § 디렉터리` 구조를 만든다. 빈 디렉터리는 만들지 않고 아래 파일로 시작한다.

- `web/src/server/env.ts`: `import 'server-only'`로 시작. zod로 서버 환경 변수를 검증해 내보낸다.
  - `GATEWAY_URL`(기본 `http://localhost:8000`, BFF → Gateway 서버 호출용)
  - `GATEWAY_PUBLIC_URL`(기본 `http://localhost:8000`, 브라우저가 SSE로 붙을 주소. BFF가 URL을 만들어 내려준다)
  - `REDIS_URL`(기본 `redis://localhost:6379`)
  - `WEB_ORIGIN`(기본 `http://localhost:3000`, CSRF Origin 검사 기준)
  - `SESSION_COOKIE_SECURE`(기본 `false`, 운영 `true`)
- `web/src/lib/problem.ts`: 백엔드 ProblemDetail(`status`, `detail?`, `code?`)의 zod 스키마와 `ApiError` 클래스(`status`, `code`). 서버·클라이언트 공용 순수 코드.
- `web/src/lib/problem.test.ts`: 스키마 파싱 단위 테스트(코드 있음/없음).
- `web/.env.example`: 위 변수와 기본값. `web/.gitignore`에 `.env*.local`, `.next`, `node_modules`, `playwright-report`, `test-results`가 포함되는지 확인한다.

### 3. 검사 스크립트: `scripts/agent/test/web-check.sh`

- `web/node_modules`가 없으면 `pnpm install --frozen-lockfile`을 먼저 실행한다.
- `lint`, `typecheck`, `test`, `format:check`를 순서대로 `run-summary.sh -C web pnpm run <작업>`으로 실행한다. 인자 `--build`가 있으면 마지막에 `build`도 실행한다.
- 하나라도 실패하면 0이 아닌 코드로 끝난다(전부 실행한 뒤 실패 목록을 출력).

### 4. 훅 확장

- `scripts/agent/hook/stop_quality.sh`와 `.githooks/pre-commit`: `web/` 아래 변경(훅은 각각 working tree / staged 기준)이 있으면 `web-check.sh`(build 제외)를 실행하고, 실패 시 기존 Kotlin 검사와 같은 방식으로 멈춘다. Kotlin 검사 동작은 그대로 둔다.
- `AGENTS.md § 6`의 `cd web && npm run dev`를 `cd web && pnpm install && pnpm dev`로 바꾸고, `scripts/agent/test/run-summary.sh` 사용법 주석의 `npm run build` 예시를 `pnpm run build`로 바꾼다.
- `AGENTS.md § 7`에 한 줄 추가: "web 변경 후 `./scripts/agent/test/web-check.sh`(빌드 확인은 `--build`)를 통과시킨다. Stop hook·pre-commit이 같은 검사를 한다."

## Acceptance Criteria

```bash
rg -q '"next": "16.4.6"' web/package.json
test -f web/src/server/env.ts && rg -q "import 'server-only'" web/src/server/env.ts
test -x scripts/agent/test/web-check.sh
rg -q 'web-check' scripts/agent/hook/stop_quality.sh .githooks/pre-commit AGENTS.md
! rg -n 'NEXT_PUBLIC_' web/src web/.env.example
test -f web/pnpm-lock.yaml && test ! -e web/package-lock.json
rg -q '"packageManager": "pnpm@' web/package.json
! rg -n 'npm run' AGENTS.md
./scripts/agent/test/web-check.sh --build
```

## 검증 절차

1. `./scripts/agent/test/web-check.sh --build`가 통과할 때까지 고친다.
2. 훅 변경은 `echo '{}' | ./scripts/agent/hook/stop_quality.sh; echo $?`로 web 변경이 있는 상태에서 실행돼 0이 나오는지 확인한다.

## 금지사항

- `NEXT_PUBLIC_` 환경 변수를 만들지 마라. 이유: 서버 설정·Gateway 주소를 클라이언트 번들에 넣지 않는다(`docs/rules/frontend.md § 백엔드 호출`).
- `next`를 `16.4.6` 외 버전(canary 포함)으로 올리지 마라. 이유: 합의된 버전이다.
- ESLint 규칙을 끄는 주석이나 `any`를 쓰지 마라. 이유: `docs/rules/frontend.md § TypeScript와 스타일`.
- 인증·세션·Redis 코드를 만들지 마라. 이유: step 5의 범위다.
- 파일 내용을 heredoc으로 쓰지 말고 Write/Edit 도구를 쓴다. 이유: 명령 정책 훅이 명령 문자열 전체를 검사한다.
