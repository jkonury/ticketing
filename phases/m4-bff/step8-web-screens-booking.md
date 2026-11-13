# Step 8: web-screens-booking

- model: 기본 / reasoning_effort: high

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `docs/rules/frontend.md § SSE`, `§ 컴포넌트`, `§ 예매 화면 규칙`, `§ 테스트`
- `phases/m4-bff/README.md § 합의된 결정` (SSE 재연결, 입장 토큰, 결제 확인 행)
- `web/src/app/api/` (step 6의 Route Handler: 스트림 티켓, 대기열, 예매, 결제, 폴링)
- `web/src/server/api/catalog.ts` (좌석 배치 조회), `web/src/lib/labels.ts`

## 작업

대기열 → 좌석 선택 → 예매 → 결제 → 완료 화면을 만든다.

### SSE 계약 (브라우저 → Gateway 직접, URL은 BFF `POST /api/streams/tickets`가 준다)

| 스트림 | 이벤트 | data |
|---|---|---|
| 대기열 `.../streams/queues/{scheduleId}` | `position` | `{position}` (바뀔 때만) |
| | `admitted` | `{admissionToken, expiresAt}` 후 서버가 스트림 종료 |
| | `removed` | 없음, 후 종료 |
| 좌석 `.../streams/schedules/{scheduleId}/seats` | `snapshot` (id 있음) | `{seats:[{seatId, status: AVAILABLE\|HELD\|SOLD, holdExpiresAt?}]}` |
| | `seat` (id 있음) | `{seatIds, status, holdExpiresAt?, at}` |

15초마다 heartbeat 주석이 온다. 티켓은 1회용(30초)이며 연결 실패 시 Gateway가 401 `SSE_TICKET_INVALID`를 준다.

### 1. SSE 훅: `web/src/features/stream/use-event-stream.ts`

```ts
useEventStream(opts: {
  request: { kind: 'queue' | 'seats'; scheduleId: number };
  handlers: Record<string, (data: unknown, id: string | null) => void>;
  enabled?: boolean;
}): { state: 'connecting' | 'open' | 'reconnecting' | 'closed' | 'failed' }
```

- 연결할 때마다 BFF에서 새 URL(티켓)을 받는다. `EventSource`의 `onerror`에서 즉시 `close()`하고(자동 재연결 금지) 지수 백오프(1s, 2s, 4s … 최대 30s, 지터)로 다시 연결한다.
- 마지막으로 받은 이벤트 id를 기억해 seats 재연결 때 티켓 요청의 `lastEventId`로 넘긴다.
- 서버가 정상 종료하는 경우(대기열 `admitted`/`removed` 후)는 핸들러가 `close`를 요청할 수 있게 하고 재연결하지 않는다.
- 티켓 발급이 401 `SESSION_EXPIRED`면 `failed`로 두고 로그인으로 보낸다. 언마운트 시 연결과 타이머를 정리한다.
- 핸들러 data는 각 화면에서 zod로 검증한다.

### 2. 예매 페이지 `app/booking/[scheduleId]/page.tsx` — 대기열 단계

- 라우트는 이 페이지 하나다(`loading.tsx`, `error.tsx` 포함). Server Component가 좌석 배치와 공연·회차 정보를 받아 클라이언트 컴포넌트 `features/booking/booking-flow.tsx`에 넘기고, 이 컴포넌트가 queue → seats → payment → done 단계를 상태로 관리한다.

- 진입 시 클라이언트 컴포넌트가 `POST /api/queues/{id}/entries`를 호출한다(사용자 행동에 따른 변경이므로 허용). 응답이 `ADMITTED`면 바로 다음 단계, 아니면 대기열 스트림으로 순번 표시.
- `admitted` 수신 시 입장 토큰과 만료 시각을 `booking-flow`의 메모리 상태로 좌석 단계에 넘긴다. URL·브라우저 저장소에 넣지 않는다.
- `removed`면 안내 후 다시 줄 서기 버튼. 페이지를 벗어나면 `DELETE .../entries/me`를 시도한다(실패 무시).

### 3. 좌석 선택

- 좌석 배치는 서버에서 `GET /schedules/{id}/seats`로 받아 props로 넘긴다(구역·열별 격자, 등급 색). 상태는 좌석 스트림 `snapshot` 후 `seat` 이벤트를 적용한다. 화면의 상태는 표시용이다.
- 최대 4석 선택. 좌석 버튼은 `aria-label`에 "A구역 3열 12번, R석, 선택 가능" 형식으로 번호와 상태를 담고 `aria-pressed`로 선택 여부를 나타낸다. 키보드로 조작 가능.
- 다른 사람이 선점·판매한 좌석이 선택돼 있으면 선택에서 빼고 안내한다.
- "예매하기": `idempotencyKey = crypto.randomUUID()`를 선택 시점에 만들어 같은 선택의 재시도에는 같은 키를 쓴다. `POST /api/bookings`. 요청 중 버튼 비활성.

### 4. 결제

- 예매 응답의 `expiresAt` 기준 남은 시간을 표시한다(클라이언트에서 5분을 따로 세지 않는다). 0이 되면 만료 안내.
- "결제하기": `POST /api/bookings/{id}/payments`(별도 멱등 키, 재시도 시 재사용) → 202 후 `GET /api/bookings/{id}`를 1초 간격, 최대 30초 폴링. `CONFIRMED` → 완료 단계, `EXPIRED`/`CANCELLED` → 결제 실패·만료 안내, 시간 초과 → "처리 중입니다. 마이페이지에서 확인하세요".
- 결제 화면에 "취소" 버튼(`POST /api/bookings/{id}/cancel`).

### 5. 완료

예매 번호, 공연, 회차, 좌석, 금액, 마이페이지 링크.

### 6. 오류 코드별 안내: `features/booking/error-messages.ts`

`SEAT_ALREADY_HELD`(해당 좌석을 선택에서 빼고 다시 고르게), `BOOKING_EXPIRED`(선점 만료), `ADMISSION_TOKEN_REQUIRED`·`ADMISSION_TOKEN_INVALID`(대기열 단계로 복귀), `RATE_LIMITED`(잠시 후 재시도), `INVALID_BOOKING_STATE`, `TOO_MANY_SEATS`, `SESSION_EXPIRED`(로그인으로), 그 밖은 일반 오류. 메시지 문자열로 분기하지 않는다.

### 7. 테스트

- `use-event-stream.test.ts`(가짜 `EventSource`·fetch·타이머): 오류 시 close 후 백오프로 새 티켓 요청, seats 재연결 시 `lastEventId` 전달, 언마운트 시 정리, 핸들러의 close 요청 후 재연결 안 함.
- 좌석 선택 컴포넌트: 5번째 선택 불가, `seat` 이벤트로 선택 좌석이 HELD되면 선택 해제와 안내, 버튼 레이블.
- 결제 단계: 요청 중 버튼 비활성, `CONFIRMED` 폴링 결과로 완료 단계 전환, `ADMISSION_TOKEN_INVALID` 응답 시 대기열 단계 복귀.

## Acceptance Criteria

```bash
test -f web/src/features/stream/use-event-stream.ts && test -f web/src/features/stream/use-event-stream.test.ts
test -f web/src/features/booking/error-messages.ts && test -f web/src/app/booking/\[scheduleId\]/page.tsx && test -f web/src/app/booking/\[scheduleId\]/error.tsx
rg -q 'SEAT_ALREADY_HELD' web/src/features/booking/error-messages.ts && rg -q 'ADMISSION_TOKEN_INVALID' web/src/features/booking/error-messages.ts
! rg -n 'localStorage|sessionStorage|NEXT_PUBLIC_' web/src
! rg -n 'accessToken|refreshToken' web/src/app web/src/features web/src/components
./scripts/agent/test/web-check.sh --build
```

## 검증 절차

1. 훅 테스트 → 컴포넌트 테스트 → `web-check.sh --build`.
2. 가능하면 로컬 스택 전체(`docker compose up -d`, core(`seed` 프로필), realtime, gateway, `pnpm dev`)에서 브라우저로 대기열 → 좌석 → 결제 → 완료를 확인하고, 두 번째 브라우저 창에서 좌석 변경이 반영되는지 본다. 못 했으면 보고한다(step 9의 E2E가 다시 확인한다).

## 금지사항

- 입장 토큰·SSE 티켓을 URL 경로·`localStorage`·`sessionStorage`·쿠키에 저장하지 마라. 이유: 메모리 상태로 충분하고 저장소 노출을 줄인다. (SSE 티켓은 Gateway 연결 URL의 쿼리에만 쓰인다.)
- `EventSource` 자동 재연결에 맡기지 마라. 이유: 티켓이 1회용이라 같은 URL 재연결은 401이 된다.
- 선점 성공 여부를 SSE 화면 상태로 판단하지 마라. 이유: 서버 응답(`POST /api/bookings`)으로만 판단한다.
- 선점 남은 시간을 클라이언트 타이머 5분으로 계산하지 마라. 이유: 서버 `expiresAt` 기준이다.
- 결제 요청 재시도에 새 멱등 키를 만들지 마라. 이유: AGENTS.md 4절 규칙 7.
