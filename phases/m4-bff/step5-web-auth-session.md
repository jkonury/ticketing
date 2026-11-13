# Step 5: web-auth-session

- model: 기본 / reasoning_effort: high

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `docs/rules/frontend.md § 토큰과 세션`, `§ 백엔드 호출`, `§ 컴포넌트`, `§ 테스트`
- `phases/m4-bff/README.md § 합의된 결정`, `§ Redis 키`
- `web/src/server/env.ts`, `web/src/lib/problem.ts` (step 4 결과)

## 작업

BFF 서버 측 세션, Gateway 공통 클라이언트(자동 갱신), 가입·로그인·로그아웃을 만든다.

### 백엔드 계약 (Gateway `GATEWAY_URL` 경유)

| 호출 | 요청 | 응답 |
|---|---|---|
| `POST /api/v1/members` | `{email, password(8~64), name(≤50), role: USER\|ORGANIZER}` | 201 `{id, email, name, role}`. 409 `EMAIL_ALREADY_EXISTS`, 400 `INVALID_REQUEST`(`errors:[{field,message}]`) |
| `POST /api/v1/auth/tokens` | `{email, password}` | 200 `{accessToken, accessExpiresIn(초), refreshToken, refreshExpiresIn(초)}`. 401 `INVALID_CREDENTIALS` |
| `POST /api/v1/auth/tokens/refresh` | `{refreshToken}` | 200 위와 같음. 401 `INVALID_REFRESH_TOKEN` |
| `POST /api/v1/auth/tokens/revoke` | `{refreshToken}` | 204 |
| `GET /api/v1/members/me` | Bearer | `{id, email, name, role}` |

오류 본문은 ProblemDetail이며 `code`가 최상위 속성이다. 401 `UNAUTHORIZED`는 access 토큰 만료·무효를 뜻한다.

### 1. 세션: `web/src/server/auth/`

```ts
// session-store.ts
type SessionData = { memberId: number; name: string; role: 'USER' | 'ORGANIZER'; accessToken: string; accessExpiresAt: number; refreshToken: string }
interface KeyValueStore { get(key): Promise<string|null>; set(key, value, opts: { pxMs: number; nx?: boolean }): Promise<boolean>; del(key): Promise<void> }
createSessionStore(kv: KeyValueStore, clock: () => number): { create(data): Promise<string>; get(id): Promise<SessionData|null>; update(id, data): Promise<void>; destroy(id): Promise<void>; tryLock(id, ttlMs): Promise<boolean>; unlock(id): Promise<void> }
// redis.ts: ioredis 기반 KeyValueStore 싱글턴 (REDIS_URL)
// session-cookie.ts: 쿠키 이름, 읽기/쓰기/지우기 (next/headers cookies())
// current-user.ts: getCurrentUser(): Promise<{ memberId, name, role } | null>  // 토큰 필드 없음
```

- 세션 ID: `crypto.randomBytes(32)` base64url. Redis 키 `bff:session:<id>`, TTL = refresh 수명. 값은 JSON이며 읽을 때 zod로 검증한다(실패하면 세션 없음으로 처리).
- 쿠키: `HttpOnly`, `SameSite=Lax`, `Path=/`, `Secure`는 `SESSION_COOKIE_SECURE`. 값은 세션 ID만.
- 갱신 락 키 `bff:refresh-lock:<id>`, `SET NX PX 10000`.

### 2. Gateway 공통 클라이언트: `web/src/server/api/gateway-client.ts`

```ts
type CallOptions<T> = {
  method?: string; path: string; body?: unknown; schema: z.ZodType<T> | null /* 204 */;
  auth: 'required' | 'optional' | 'none'; timeoutMs?: number /* 기본 3000 */;
  headers?: { idempotencyKey?: string; admissionToken?: string };
  cache?: { revalidateSeconds: number } /* auth:'none'인 공개 GET만 */;
}
createGatewayClient(deps: { fetch; sessions; sessionId: () => Promise<string|null>; clock; baseUrl }): { call<T>(opts: CallOptions<T>): Promise<T> }
export const gateway  // 실제 의존성으로 만든 기본 인스턴스
class SessionExpiredError extends Error {}
```

- 인증 호출 전 access 만료가 30초 이내면 갱신한다. 응답이 401이면 한 번 갱신 후 한 번만 재시도한다.
- **동시 갱신은 한 번만**: 같은 프로세스에서는 세션 ID별 진행 중 Promise를 공유한다(single-flight). 프로세스 간에는 락을 잡은 쪽만 refresh를 호출하고, 못 잡은 쪽은 최대 5초 동안 세션을 다시 읽어 토큰이 바뀌면 그것을 쓴다.
- refresh가 401이면 세션을 파기하고 `SessionExpiredError`를 던진다. 세션이 없는데 `auth: 'required'`면 같은 오류.
- 실패 응답은 `lib/problem.ts`로 파싱해 `ApiError(status, code)`를 던진다. 응답 본문은 zod로 검증하고 실패하면 `ApiError(502, 'BAD_UPSTREAM_RESPONSE')`. 타임아웃은 `AbortSignal.timeout`, 초과 시 `ApiError(504, 'UPSTREAM_TIMEOUT')`.
- 사용자별 호출은 `cache: 'no-store'`. `cache` 옵션이 있을 때만 `next.revalidate`를 쓴다.
- 클라이언트 생성 함수는 의존성을 주입받아 테스트에서 가짜 fetch·store·clock을 쓸 수 있게 한다.

### 3. CSRF: `web/src/server/auth/csrf.ts`

`assertSameOrigin(headers: Headers): void` — `Origin`(없으면 `Referer`의 origin)이 `WEB_ORIGIN`과 다르면 `ApiError(403, 'CSRF_REJECTED')`. 상태를 바꾸는 모든 Route Handler와 Server Action이 첫 줄에서 호출한다.

### 4. 가입·로그인·로그아웃

- `web/src/server/auth/auth-service.ts`: `signup`, `login`(토큰 발급 → `/members/me` → 세션 생성 → 쿠키), `logout`(revoke 호출, 실패해도 세션 삭제와 쿠키 삭제).
- 화면: `app/login/page.tsx`, `app/signup/page.tsx`(역할 선택 포함), 폼은 Server Action(`features/auth/actions.ts`)으로 제출하고 `useActionState`로 오류 코드별 메시지를 보여 준다. 로그인 성공 시 `?next=` 경로(같은 사이트 상대 경로만 허용)나 `/`로 이동.
- `app/layout.tsx` 헤더에 현재 사용자 이름·역할과 로그아웃 버튼(Server Action). 비로그인이면 로그인 링크.
- `web/src/proxy.ts`: `/my`, `/organizer`, `/booking` 아래 경로에 세션 쿠키가 없으면 `/login?next=...`로 보낸다(쿠키 존재만 확인하는 편의 기능이다. 실제 검증은 서버 호출에서 한다).
- 페이지에서 `SessionExpiredError`를 만나면 `redirect('/login')`하는 헬퍼(`server/auth/require-user.ts`)를 둔다.

### 5. 테스트 (Vitest, 가짜 의존성)

- `gateway-client.test.ts`: 만료 임박 토큰으로 동시 5건 호출 시 refresh 1회; 401 응답 → 갱신 → 재시도 1회(두 번째 401이면 그대로 오류); refresh 401 → 세션 파기 + `SessionExpiredError`; 다른 인스턴스가 락을 잡고 있으면 refresh를 호출하지 않고 갱신된 세션을 사용; 타임아웃 → 504 `UPSTREAM_TIMEOUT`; 스키마 불일치 → 502.
- `session-store.test.ts`: 생성·조회·파기, 손상된 JSON은 null, TTL 전달.
- `csrf.test.ts`: 같은 origin 통과, 다른 origin·누락 거부, Referer 대체.
- `auth-service.test.ts`: 로그아웃 시 revoke 실패해도 세션 삭제.

## Acceptance Criteria

```bash
test -f web/src/server/api/gateway-client.test.ts
test -f web/src/server/auth/csrf.ts && test -f web/src/proxy.ts
test -z "$(rg --files-without-match "import 'server-only'" web/src/server -g '*.ts' -g '!*.test.ts')"
! rg -n 'NEXT_PUBLIC_|localStorage|sessionStorage' web/src
! rg -n 'accessToken|refreshToken' web/src/app web/src/features web/src/components
./scripts/agent/test/web-check.sh --build
```

## 검증 절차

1. 단위 테스트부터 통과시킨 뒤 `web-check.sh --build`를 실행한다.
2. 가능하면 로컬 스택(`docker compose up -d`, `:core:bootRun`, `:gateway:bootRun`)에서 `pnpm dev`로 가입 → 로그인 → 로그아웃을 확인하고, 브라우저 쿠키에 세션 ID 하나만 있는지 본다. 스택을 띄울 수 없으면 확인하지 못했다고 보고한다.

## 금지사항

- 토큰을 쿠키·응답 본문·클라이언트 컴포넌트 props·로그에 넣지 마라. 이유: AGENTS.md 4절 규칙 5, M4 완료 기준.
- `SessionData`나 토큰 필드를 `server/` 밖에서 import하지 마라. 이유: 토큰 접근 경로를 `server/auth`, `server/api`로 한정한다.
- 갱신 실패 시 재시도 루프를 만들지 마라. 이유: refresh 토큰 재사용은 회원 전체 토큰 폐기를 일으킨다.
- `next` 경로 리다이렉트에 절대 URL을 허용하지 마라. 이유: 오픈 리다이렉트.
- 브라우저에서 Gateway를 직접 호출하는 코드를 만들지 마라. 이유: 예외는 SSE뿐이다.
