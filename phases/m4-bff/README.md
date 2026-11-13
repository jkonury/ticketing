# Phase M4: Next.js BFF

- 기준: `docs/roadmap.md § M4. Next.js BFF`
- 목표: `web`(Next.js App Router)을 화면 겸 BFF로 만든다. 토큰은 BFF 서버 측 세션에만 두고, 조합 화면 3종과 예매 전 흐름 화면을 구현한다.
- 완료 기준: 브라우저에서 전체 예매 시나리오가 동작하고, 브라우저 저장소와 네트워크 응답 어디에도 JWT가 노출되지 않는다. step 9의 Playwright E2E로 확인한다.
- 각 step은 독립 세션에서 순서대로 실행한다. 앞 step의 AC가 통과해야 다음 step을 시작한다.

## 합의된 결정 (2026-11-12)

| 주제 | 결정 |
|---|---|
| 버전 | Next.js 16.4.6 (App Router, TypeScript strict), Node 24, pnpm(설치된 12.18.0, `packageManager`로 고정). 미들웨어는 Next 16의 `proxy.ts` |
| 도구 | Tailwind CSS, ESLint + Prettier, Vitest + Testing Library(단위·컴포넌트), Playwright(E2E), zod(경계 검증) |
| 세션 저장 | Redis `bff:session:<sessionId>`에 access/refresh 토큰과 만료 시각을 둔다. 쿠키에는 불투명 세션 ID만(`HttpOnly`, `Secure`(로컬 http 예외), `SameSite=Lax`, `Path=/`). 세션 TTL은 refresh 수명(14일)에 맞춘다 |
| 토큰 갱신 | access 만료 30초 전이거나 하위 API가 401이면 갱신한다. 프로세스 안 single-flight + Redis `bff:refresh-lock:<sessionId>` `SET NX PX`로 인스턴스 간에도 한 번만 갱신한다(refresh 로테이션 재사용 감지 때문). 갱신 실패 시 세션 파기 후 로그인으로 보낸다 |
| 로그아웃 | 코어에 `POST /api/v1/auth/tokens/revoke {refreshToken}`(공개, 멱등 204)을 추가한다. BFF는 호출 후 세션을 지운다. 호출 실패여도 세션은 지운다 |
| 조합용 조회 API | 코어에 추가한다. 각 모듈은 자기 스키마만 jOOQ로 조회한다. 아래 "추가 코어 API" 표 |
| 내 예매 여부 | `GET /bookings/me`에 `performanceId` 필드와 `?performanceId=` 필터를 더한다 |
| 결제 상태 | payment에 `GET /api/v1/payments/me?bookingIds=`를 더해 마이페이지에서 예매 목록과 별도로 조합한다(부분 실패 대상) |
| 입장 토큰 | 브라우저가 대기열 SSE `admitted` 이벤트로 받는다(불투명 난수, JWT 아님). 예매 생성 시 BFF Route Handler에 넘기고 BFF가 `X-Admission-Token`으로 전달한다 |
| SSE 재연결 | 티켓이 1회용이라 브라우저 `EventSource`의 자동 재연결을 쓰지 않는다. 오류 시 닫고 지수 백오프 뒤 새 티켓으로 다시 연결한다. 헤더를 실을 수 없으므로 realtime 좌석 스트림이 `?lastEventId=` 쿼리도 받는다(헤더가 있으면 헤더 우선). Gateway는 `ticket` 외 쿼리를 그대로 전달한다 |
| 결제 확인 | 결제 요청(202) 뒤 브라우저가 BFF를 거쳐 `GET /bookings/{id}`를 1초 간격으로 폴링해 `CONFIRMED`/실패를 판단한다. 최대 대기 시간 후에는 마이페이지로 안내 |
| CSRF | 상태를 바꾸는 Route Handler는 `Origin`(없으면 `Referer`)이 `web` origin과 같은지 검사한다. Server Action은 Next 내장 검사에 더해 같은 유틸을 쓴다 |
| 조합 핵심/부가 | 공연 상세: 공연 정보 핵심, 잔여 좌석·내 예매 부가. 마이페이지: 예매 목록 핵심, 결제 상태 부가. 주최자 대시보드: 판매 현황·정산 둘 다 부가(둘 다 실패하면 오류 화면) |
| 주최자 등록 | 공연장(구역 수 × 열 수 × 열당 좌석 수, 열 범위별 등급) → 공연(가격) → 회차를 한 화면 흐름으로 최소 구현. 초기 데이터는 기존 `seed` 프로필 |
| 브라우저 E2E | `scripts/e2e/run.sh`에 `--web` 옵션을 더한다. 기존 스택에 `web`(`next build && next start`, 3000)을 띄우고 Playwright를 실행한다. 로그 `build/e2e/logs/web.log` |

## 추가 코어 API

| 모듈 | API | 내용 |
|---|---|---|
| member | `POST /api/v1/auth/tokens/revoke` | 공개. 본문 `refreshToken`. 해시로 찾아 폐기, 없어도 204 |
| payment | `GET /api/v1/payments/me?bookingIds=1,2` | USER. 최대 50개. 본인 결제만 `[{bookingId, status, failureReason?, approvedAt?, refundedAt?}]` |
| booking | `GET /api/v1/schedules/availability?scheduleIds=1,2` | 공개. 최대 50개. `[{scheduleId, totalSeats, availableSeats}]`. 가용 = 좌석 수 − 판매 − 만료 전 PENDING 예매 좌석 |
| booking | `GET /api/v1/bookings/me` 확장 | 항목에 `performanceId`, 쿼리 `performanceId` 필터 |
| booking | `GET /api/v1/organizer/sales` | ORGANIZER. JWT 회원 ID = `booking.schedule.organizer_id`. 회차별 `{performanceId, performanceTitle, scheduleId, startsAt, totalSeats, soldSeats, confirmedAmount}` |

## Step

| N | 이름 | 대상 | model / reasoning_effort |
|---|---|---|---|
| 0 | [bff-decisions-docs](./step0-bff-decisions-docs.md) | docs (ADR 0022, integrations·redis·flows 문서) | haiku / medium |
| 1 | [core-revoke-payment-read](./step1-core-revoke-payment-read.md) | core `member`(revoke), `payment`(결제 상태 조회) | 기본 / medium |
| 2 | [core-booking-read](./step2-core-booking-read.md) | core `booking`(잔여 좌석, 내 예매 필터, 주최자 판매 현황) | 기본 / medium |
| 3 | [realtime-last-event-id-query](./step3-realtime-last-event-id-query.md) | realtime 좌석 스트림 `lastEventId` 쿼리 | 기본 / low |
| 4 | [web-scaffold](./step4-web-scaffold.md) | web 생성, 도구·디렉터리·환경 변수, `web-check.sh`, 훅 | 기본 / medium |
| 5 | [web-auth-session](./step5-web-auth-session.md) | `server/auth`, `server/api` 공통 클라이언트(갱신·single-flight·락), 가입·로그인·로그아웃, CSRF | 기본 / high |
| 6 | [web-api-aggregate](./step6-web-api-aggregate.md) | `server/api` 도메인 클라이언트(zod), `server/aggregate` 3종, 브라우저용 Route Handler | 기본 / high |
| 7 | [web-screens-browse](./step7-web-screens-browse.md) | 레이아웃, 공연 목록·상세, 마이페이지, 주최자 대시보드·등록 | 기본 / medium |
| 8 | [web-screens-booking](./step8-web-screens-booking.md) | SSE 훅, 대기열, 좌석 선택, 예매·결제·완료, 오류 코드별 안내 | 기본 / high |
| 9 | [web-e2e](./step9-web-e2e.md) | Playwright 시나리오, JWT 비노출 검사, `run.sh --web` | 기본 / high |
| 10 | [docs-finalize](./step10-docs-finalize.md) | architecture·roadmap·AGENTS.md 동기화 | haiku / medium |

## Redis 키 (이 phase에서 추가)

| 키 | 자료구조 | 쓰는 쪽 | 읽는 쪽 |
|---|---|---|---|
| `bff:session:<sessionId>` | String(JSON: 회원 ID, 역할, access, access 만료 시각, refresh), TTL 14일 | web | web |
| `bff:refresh-lock:<sessionId>` | String, `SET NX PX` 10초 | web | web |
