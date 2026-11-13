# Step 6: web-api-aggregate

- model: 기본 / reasoning_effort: high

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `docs/rules/frontend.md § 백엔드 호출`, `§ 조합 (Aggregation)`, `§ SSE`, `§ 테스트`
- `phases/m4-bff/README.md § 합의된 결정` (조합 핵심/부가, 입장 토큰, 결제 확인 행)
- `web/src/server/api/gateway-client.ts`, `web/src/server/auth/csrf.ts`, `web/src/server/auth/require-user.ts`, `web/src/server/env.ts`, `web/src/lib/problem.ts` (step 4·5 결과)

## 작업

도메인별 Gateway 클라이언트, 화면 조합 함수 3종, 브라우저가 호출하는 BFF Route Handler를 만든다. 화면은 만들지 않는다.

### 백엔드 계약 (모두 `/api/v1` 기준, ProblemDetail `code`)

| 도메인 | 호출 | 인증 | 응답 |
|---|---|---|---|
| catalog | `GET /performances?cursor&size` | 없음 | `{items:[{id, title, venueName, firstStartsAt?}], nextCursor?}` |
| catalog | `GET /performances/{id}` | 없음 | `{id, title, description, venueId, venueName, prices:[{grade, price}], schedules:[{id, startsAt}]}`. 404 `PERFORMANCE_NOT_FOUND` |
| catalog | `GET /schedules/{id}/seats` | 없음 | `[{seatId, section, row, number, grade, price}]` |
| catalog | `POST /venues`, `POST /performances`, `POST /performances/{id}/schedules` | ORGANIZER | 201 `{id, seatCount?}` (요청 형식은 step 7에서 사용, 여기서는 클라이언트 함수만) |
| booking | `GET /schedules/availability?scheduleIds=` | 없음 | `[{scheduleId, totalSeats, availableSeats}]` |
| booking | `GET /bookings/me?cursor&size&performanceId` | USER | `{items:[{bookingId, status, performanceId, performanceTitle, startsAt, seatCount, totalAmount, createdAt}], nextCursor?}` |
| booking | `GET /bookings/{id}` | USER | `{bookingId, status, performanceTitle, startsAt, totalAmount, expiresAt, createdAt, seats:[{seatId, label, price}]}` |
| booking | `POST /bookings` | USER + `Idempotency-Key` + `X-Admission-Token` | 201/200 `{bookingId, status, totalAmount, expiresAt, seats:[...]}`. 409 `SEAT_ALREADY_HELD`(`seatIds`), 403 `ADMISSION_TOKEN_REQUIRED`/`ADMISSION_TOKEN_INVALID`, 429 `RATE_LIMITED` |
| booking | `POST /bookings/{id}/payments` | USER + `Idempotency-Key` | 202 `{bookingId, status}`. 409 `BOOKING_EXPIRED`/`INVALID_BOOKING_STATE` |
| booking | `POST /bookings/{id}/cancel` | USER | 200 예매 응답 |
| booking | `GET /organizer/sales` | ORGANIZER | `[{performanceId, performanceTitle, scheduleId, startsAt, totalSeats, soldSeats, confirmedAmount}]` |
| payment | `GET /payments/me?bookingIds=` | USER | `[{bookingId, status, failureReason?, approvedAt?, refundedAt?}]` |
| settlement | `GET /settlements?from&to` (YYYY-MM-DD, 최대 31일) | ORGANIZER | `[{settlementDate, performanceId, saleCount, saleAmount, refundCount, refundAmount, netAmount, feeRate, feeAmount, payoutAmount}]` |
| member | `POST /sse-tickets` | 인증 | 201 `{ticket, expiresAt}` |
| queue | `POST /queues/{scheduleId}/entries`, `GET .../entries/me`, `DELETE .../entries/me` | 인증 | `{status: WAITING\|ADMITTED, position?, admissionToken?, expiresAt?}` / 204. 404 `SCHEDULE_NOT_FOUND`, `QUEUE_ENTRY_NOT_FOUND` |

금액(`BigDecimal`)은 JSON 숫자로 온다. 시각은 ISO-8601 문자열이다.

### 1. 도메인 클라이언트: `web/src/server/api/{catalog,booking,payment,settlement,member,queue}.ts`

- 각 파일은 zod 스키마와 함수만 둔다. 모든 호출은 `gateway.call`을 거친다.
- 공개 목록·상세 조회는 `cache: { revalidateSeconds: 30 }`, 그 밖은 no-store(기본).

### 2. 조합 함수: `web/src/server/aggregate/`

```ts
// lib/section.ts (공용 타입)
type Section<T> = { status: 'ok'; data: T } | { status: 'unavailable' }

// performance-detail.ts — 핵심: 공연 정보. 부가: 잔여 좌석, 내 예매
getPerformanceDetail(id: number): Promise<PerformanceDetailView>
// my-page.ts — 핵심: 예매 목록. 부가: 결제 상태
getMyPage(cursor?: number): Promise<MyPageView>
// organizer-dashboard.ts — 부가 두 개(판매 현황, 정산 기간). 둘 다 실패하면 throw
getOrganizerDashboard(range: { from: string; to: string }): Promise<OrganizerDashboardView>
```

- 각 파일 상단 주석에 핵심/부가 구분을 적는다.
- 핵심 실패는 그대로 throw(404는 `notFound()`로 이어질 수 있게 `ApiError` 유지). 부가 실패는 해당 `Section`을 `unavailable`로 둔다. `SessionExpiredError`는 부가 데이터여도 삼키지 않는다.
- 공연 상세: 공연 정보를 받은 뒤 잔여 좌석(전체 회차 ID 한 번에)과 내 예매(로그인한 경우만, `performanceId` 필터)를 **병렬**로 부른다. 비로그인이면 내 예매는 `null`(실패와 구분).
- 마이페이지: 예매 목록을 받은 뒤 결제 상태를 해당 예매 ID들로 한 번 부른다. 예매가 없으면 결제 호출을 하지 않는다. 각 예매에 결제 상태를 붙인 화면용 타입으로 가공한다.
- 주최자 대시보드: 판매 현황과 정산을 병렬로 부른다. 공연별 판매 합계(좌석·금액)를 계산해 넣는다.
- 부가 하위 호출의 타임아웃은 2초, 핵심은 3초.
- 반환 타입은 화면용으로 가공한 타입이며 백엔드 응답 객체를 그대로 내보내지 않는다.

### 3. 브라우저용 Route Handler: `web/src/app/api/`

모든 핸들러는 `server/` 함수만 호출한다. 오류는 `{ code, status }` JSON으로 돌려준다(백엔드 본문을 그대로 전달하지 않는다). `SessionExpiredError` → 401 `SESSION_EXPIRED`. 상태를 바꾸는 메서드는 첫 줄에서 `assertSameOrigin`.

| BFF 경로 | 동작 |
|---|---|
| `POST /api/streams/tickets` 본문 `{kind: 'queue'\|'seats', scheduleId, lastEventId?}` | SSE 티켓 발급 후 `{url, expiresAt}` 반환. `url` = `GATEWAY_PUBLIC_URL` + `/api/v1/streams/queues/{id}` 또는 `/api/v1/streams/schedules/{id}/seats` + `?ticket=`(+ `&lastEventId=` seats만) |
| `POST /api/queues/[scheduleId]/entries`, `GET`·`DELETE /api/queues/[scheduleId]/entries/me` | 대기열 등록·조회·이탈 |
| `POST /api/bookings` 본문 `{scheduleId, seatIds, admissionToken, idempotencyKey}` | 예매 생성. 키는 브라우저가 만들어 재시도 때 재사용한다. 409 `SEAT_ALREADY_HELD`는 `seatIds`도 함께 돌려준다 |
| `GET /api/bookings/[id]` | 결제 확인 폴링용 `{bookingId, status, expiresAt}` |
| `POST /api/bookings/[id]/payments` 본문 `{idempotencyKey}`, `POST /api/bookings/[id]/cancel` | 결제 요청, 취소 |

### 4. 테스트 (Vitest, 가짜 하위 API)

- 조합 3종: 핵심 실패 → throw; 부가 하나 실패·타임아웃 → 해당 섹션만 `unavailable`; 비로그인 공연 상세 → 내 예매 `null`이며 예매 API 미호출; 병렬성(두 부가 호출이 서로의 완료를 기다리지 않고 시작됨); 마이페이지 예매 0건 → 결제 API 미호출; 대시보드 둘 다 실패 → throw.
- Route Handler: 다른 Origin POST → 403 `CSRF_REJECTED`; 티켓 엔드포인트 응답 키가 `url`, `expiresAt`뿐이며 seats만 `lastEventId`를 붙임; 예매 생성이 `Idempotency-Key`·`X-Admission-Token` 헤더로 전달됨; 오류 응답에 백엔드 `detail`이 실리지 않음.

## Acceptance Criteria

```bash
test -f web/src/server/aggregate/performance-detail.ts && test -f web/src/server/aggregate/my-page.ts && test -f web/src/server/aggregate/organizer-dashboard.ts
test -f web/src/app/api/streams/tickets/route.ts
test -z "$(rg --files-without-match "import 'server-only'" web/src/server -g '*.ts' -g '!*.test.ts')"
! rg -n 'fetch\(' web/src/app web/src/features web/src/components -g '!*.test.*'
! rg -n ': any\b|as any' web/src
./scripts/agent/test/web-check.sh --build
```

## 검증 절차

1. 조합 함수 테스트 → Route Handler 테스트 → `web-check.sh --build` 순으로 통과시킨다.

## 금지사항

- 조합 함수에서 독립 호출을 순차 `await`로 잇지 마라. 이유: `docs/rules/frontend.md § 조합`. 공연 정보 → 부가 호출처럼 의존이 있는 경우만 순차로 둔다.
- Route Handler나 컴포넌트에서 Gateway URL로 `fetch`하지 마라. 이유: Gateway 호출은 `server/api`로만 한다.
- SSE 티켓 응답에 회원 ID·토큰 등 `url`·`expiresAt` 외 값을 넣지 마라. 이유: 브라우저에 필요한 최소 정보만 준다.
- 사용자별 응답을 캐시하지 마라. 이유: 다른 사용자에게 데이터가 샐 수 있다.
- 화면 컴포넌트를 만들지 마라. 이유: step 7·8의 범위다.
