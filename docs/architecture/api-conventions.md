# API 규약 (§7, §8)

> [← 아키텍처 개요](../architecture.md) · 범위: 코어·realtime·Gateway의 HTTP API 공통 규칙
> 함께 보기: [integrations.md](./integrations.md), [events.md](./events.md), [ADR 0020](../adr/0020-gateway-verification-contracts.md), [ADR 0022](../adr/0022-bff-session-and-aggregation-apis.md), [ADR 0023](../adr/0023-schedule-detail-api.md)

## 1. 호출 경로

```
브라우저 ─▶ Next.js BFF ─▶ Gateway(18000) ─▶ 코어(18080)
브라우저 ─(SSE, 단기 티켓)─▶ Gateway(18000) ─▶ realtime(18081)
```

- 브라우저는 코어 API를 직접 부르지 않고 BFF Route Handler를 거친다. SSE만 예외다.
- BFF·클라이언트는 Gateway를 거쳐 호출한다.

### Gateway 라우팅

| 경로 | 대상 | Gateway 처리 |
|---|---|---|
| `/api/v1/queues/**` | realtime | JWT 필수, 회원 ID를 `X-Member-Id`로 전달, rate limit |
| `/api/v1/streams/**` | realtime | SSE 티켓 소비, rate limit |
| `POST /api/v1/bookings` | 코어 | JWT 필수, 입장 토큰 검증, rate limit |
| `POST /api/v1/coupons/*/claims` | 코어 | 전용 rate limit 버킷(`coupon-claim`, 보충 1·버스트 3. 회원당 1회 시나리오라 429는 검증하지 못한 임시값 확정, [coupon-drop.md](../performance/m5.5/coupon-drop.md)) |
| `/api/v1/**` 그 외 | 코어 | rate limit |

## 2. 기본 규칙

- 경로는 `/api/v1/` 아래에 둔다. 호환되지 않는 변경은 `v2`로 올린다.
- 리소스는 복수형 명사로 쓴다. `/bookings`, `/performances`. 본인 데이터는 `/me`를 쓴다(`/bookings/me`).
- 상태를 바꾸는 동작은 하위 리소스 `POST`로 표현한다. `/bookings/{id}/cancel`, `/bookings/{id}/payments`.
- 본문은 JSON, 인코딩은 UTF-8이다. 금액은 원 단위 정수, 시각은 ISO-8601이다.
- 사용자 입력은 컨트롤러 경계에서 검증한다(`@Valid`, `@Size` 등). 검증 실패는 400 `INVALID_REQUEST`다.

## 3. 인증·인가

| 구분 | 규칙 |
|---|---|
| 토큰 | `Authorization: Bearer <access JWT>`. HS256, 수명 15분, `sub`=회원 ID, `role` 클레임 |
| 공개 API | 공연·회차 조회(`GET /api/v1/performances/**`, `GET /api/v1/schedules/**`), 회원 가입, 로그인, 토큰 갱신·폐기, 결제 웹훅, `/actuator/health` |
| 역할 | `USER`(예매·결제·내 정보), `ORGANIZER`(공연·회차 등록, 정산·판매 현황) |
| 인가 방식 | 코어 컨트롤러의 `@PreAuthorize("hasRole(...)")` |

- Gateway는 `Authorization`이 있으면 모든 경로에서 JWT 서명을 검증하고 실패하면 401 `UNAUTHORIZED`로 응답한다. 클라이언트가 보낸 `X-Member-Id`는 지운다. 코어도 JWT를 자체 검증한다.
- **JWT는 브라우저에 노출하지 않는다.** 토큰은 BFF 서버 측에만 있고 브라우저는 httpOnly 세션 쿠키만 가진다.
- 본인 데이터 API는 요청 파라미터의 회원 ID가 아니라 JWT의 회원 ID로 조회한다. 다른 회원의 예매에 접근하면 403 `NOT_BOOKING_OWNER`로 응답한다.
- 웹훅은 JWT 대신 서명으로 인증한다(`X-FakePg-Timestamp`, `X-FakePg-Signature`, [integrations.md §10](./integrations.md#10-외부-연동)).

## 4. 요청 헤더

| 헤더 | 사용 | 규칙 |
|---|---|---|
| `Authorization` | 인증 필요 API | `Bearer <access JWT>` |
| `Idempotency-Key` | `POST /api/v1/bookings`, `POST /api/v1/bookings/{id}/payments` | 필수, 1~100자 |
| `X-Admission-Token` | `POST /api/v1/bookings` | 필수. realtime이 발급한 입장 토큰. 회원·회차와 일치해야 하며 Gateway가 검증 |
| `Last-Event-ID` | 좌석 SSE 재연결 | 없으면 쿼리 `lastEventId` 사용. 둘 다 있으면 헤더 우선 |
| `X-Member-Id` | Gateway → realtime | 내부 전달용. 클라이언트가 보내도 Gateway가 제거한다 |

### 멱등성

- 예매 생성은 `(회원, Idempotency-Key)`가 같으면 같은 예매를 돌려준다. 새로 만들면 **201**, 이미 있으면 **200**이다.
- 결제 요청은 예매 하나에 결제 하나가 되도록 서버가 PG 멱등 키를 정한다. 헤더는 재시도 식별용으로 받는다. 접수되면 **202**와 `{bookingId, status}`를 반환한다.
- 결제 웹훅은 `eventId`로 중복을 제거한다.
- 클라이언트는 네트워크 오류 뒤 **같은 키로 재시도**한다. 새 키를 만들면 중복 예매가 생길 수 있다.

## 5. 응답

### 성공

| 상황 | 상태 |
|---|---|
| 조회 | 200 |
| 생성 | 201. 멱등 재호출은 200 |
| 비동기 접수(결제 요청) | 202 |
| 본문 없는 성공(토큰 폐기) | 204 |

### 오류 (RFC 9457 ProblemDetail)

모든 오류는 `application/problem+json` 형태로 `status`, `detail`, 그리고 안정적인 문자열 `code`를 가진다. **클라이언트는 `code`로 분기하고 `detail` 문구에 의존하지 않는다.** 변환은 코어의 `ApiExceptionHandler` 한 곳에서 한다.

```json
{ "status": 409, "detail": "이미 선점되었거나 판매된 좌석이 있습니다.", "code": "SEAT_ALREADY_HELD" }
```

- 도메인 오류는 모듈별 `DomainException`을 상속해 `code`와 HTTP 상태를 정한다. 코드에 따라 `seatIds` 같은 부가 필드가 붙는다.
- 검증 오류는 `code: INVALID_REQUEST`이고 본문 검증 실패에는 `errors: [{field, message}]`가 붙는다.
- Gateway와 realtime도 같은 모양(`status`, `detail`, `code`)으로 응답한다.

### 주요 오류 코드

| 상태 | code | 의미 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 요청 값 검증 실패 |
| 400 | `TOO_MANY_SEATS`, `NO_SEATS`, `DUPLICATE_SEAT` | 좌석 수·중복 규칙 위반(1회 최대 4매) |
| 400 | `DUPLICATE_VENUE_SEAT`, `DUPLICATE_PRICE_GRADE`, `PRICE_MISSING_FOR_GRADE` | 공연·회차 등록 입력 오류 |
| 400 | `INVALID_PAYMENT_WEBHOOK` | 웹훅 입력 오류 |
| 401 | `UNAUTHORIZED` | 토큰 없음·검증 실패(Gateway) |
| 401 | `INVALID_CREDENTIALS`, `INVALID_REFRESH_TOKEN` | 로그인·갱신 실패 |
| 401 | `INVALID_WEBHOOK_SIGNATURE` | 웹훅 서명 불일치 |
| 401 | `SSE_TICKET_INVALID` | SSE 티켓 만료·재사용(Gateway) |
| 403 | `ADMISSION_TOKEN_REQUIRED`, `ADMISSION_TOKEN_INVALID` | 입장 토큰 없음·무효. 클라이언트는 대기열로 돌려보낸다 |
| 403 | `NOT_BOOKING_OWNER`, `NOT_PERFORMANCE_OWNER` | 소유자가 아님 |
| 404 | `BOOKING_NOT_FOUND`, `SCHEDULE_NOT_FOUND`, `SEAT_NOT_FOUND`, `PERFORMANCE_NOT_FOUND`, `VENUE_NOT_FOUND`, `MEMBER_NOT_FOUND` | 대상 없음 |
| 409 | `SEAT_ALREADY_HELD` | 좌석이 이미 선점·판매됨 |
| 409 | `INVALID_BOOKING_STATE`, `BOOKING_EXPIRED` | 현재 예매 상태에서 불가능한 동작 |
| 409 | `EMAIL_ALREADY_EXISTS`, `DUPLICATE_SCHEDULE` | 중복 |
| 409 | `COUPON_PENDING` | 쿠폰 예약 응답을 기다리는 중이라 결제 요청 불가 |
| 404 | `COUPON_NOT_FOUND` | 쿠폰 없음 |
| 409 | `COUPON_NOT_ISSUABLE`, `COUPON_ALREADY_CLAIMED`, `COUPON_SOLD_OUT` | 쿠폰 받기 실패(발급 불가·중복·품절) |
| 409 | `COUPON_NOT_REVOCABLE`, `COUPON_DEFINITION_LOCKED`, `COUPON_VALID_UNTIL_SHORTENED`, `INVALID_COUPON_STATE`, `COUPON_ORDER_MISMATCH` | 쿠폰 회수·수정·상태 전이 규칙 위반 |
| 409 | `CONCURRENT_MODIFICATION` | 낙관적 락 충돌. 다시 시도하면 된다 |
| 429 | `RATE_LIMITED` | 요청 한도 초과(Gateway) |

전체 목록의 원본은 코어의 각 모듈 `*Exceptions.kt`와 Gateway 필터 코드다. 새 오류 코드를 추가하면 이 표도 갱신한다.

## 6. 페이지네이션

목록은 **커서 방식**이다. offset은 쓰지 않는다. 좌석 같은 변동이 큰 목록에서 건너뜀·중복이 생기기 때문이다.

- 요청: `?cursor=<마지막 항목 ID>&size=<개수>`. `cursor`가 없으면 첫 페이지다.
- 응답: `{ "items": [...], "nextCursor": <ID 또는 null> }`. `nextCursor`가 `null`이면 마지막이다.
- 적용 API: `GET /api/v1/performances`, `GET /api/v1/bookings/me`.
- 결과 크기가 도메인 규칙으로 제한되면 페이지네이션을 생략한다. 예: `GET /api/v1/settlements`는 조회 기간이 최대 31일이다.
- 여러 ID를 한 번에 묻는 조회는 쉼표 구분 목록과 상한을 둔다. `?scheduleIds=1,2`, `?bookingIds=1,2`는 최대 50개다.

## 7. API 목록

### 코어

| 메서드·경로 | 권한 | 설명 |
|---|---|---|
| `POST /api/v1/members` | 공개 | 회원 가입 |
| `GET /api/v1/members/me` | 로그인 | 내 정보 |
| `POST /api/v1/auth/tokens` | 공개 | 로그인(access·refresh 발급) |
| `POST /api/v1/auth/tokens/refresh` | 공개 | refresh 로테이션 |
| `POST /api/v1/auth/tokens/revoke` | 공개, 멱등 | refresh 폐기. 없어도 204 |
| `POST /api/v1/sse-tickets` | 로그인 | 1회용 SSE 티켓 발급(TTL 30초) |
| `GET /api/v1/performances` | 공개 | 공연 목록(커서) |
| `GET /api/v1/performances/{id}` | 공개 | 공연 상세 |
| `GET /api/v1/schedules/{id}` | 공개 | 회차 상세([ADR 0023](../adr/0023-schedule-detail-api.md)) |
| `GET /api/v1/schedules/{id}/seats` | 공개 | 회차 좌석 |
| `GET /api/v1/schedules/availability?scheduleIds=` | 공개 | 회차별 잔여 좌석 |
| `POST /api/v1/venues` | ORGANIZER | 공연장 등록 |
| `POST /api/v1/performances` | ORGANIZER | 공연 등록 |
| `POST /api/v1/performances/{performanceId}/schedules` | ORGANIZER | 회차 등록 |
| `POST /api/v1/bookings` | USER | 예매 생성. `Idempotency-Key`, `X-Admission-Token` 필요 |
| `GET /api/v1/bookings/me` | USER | 내 예매 목록(커서, `performanceId` 필터) |
| `GET /api/v1/bookings/{id}` | USER | 예매 상세 |
| `PUT /api/v1/bookings/{id}/coupon` | USER | 쿠폰 적용·교체 |
| `DELETE /api/v1/bookings/{id}/coupon` | USER | 쿠폰 해제 |
| `POST /api/v1/bookings/{id}/cancel` | USER | 예매 취소 |
| `POST /api/v1/bookings/{id}/payments` | USER | 결제 요청(202) |
| `GET /api/v1/payments/me?bookingIds=` | USER | 내 결제 상태 |
| `POST /api/v1/payments/webhooks/fake-pg` | 서명 | PG 웹훅 |
| `GET /api/v1/organizer/sales` | ORGANIZER | 판매 현황 |
| `GET /api/v1/settlements` | ORGANIZER | 정산 조회 |
| `GET /api/v1/coupons` | 로그인 | 받을 수 있는 쿠폰 목록 |
| `POST /api/v1/coupons/{definitionId}/claims` | 로그인 | 쿠폰 받기(선착순 포함, ADR 0037) |
| `GET /api/v1/me/coupons` | 로그인 | 내 쿠폰 |
| `GET /api/v1/admin/members` | ADMIN | 회원 목록 |
| `PUT /api/v1/admin/members/{id}/role` | ADMIN | 역할 변경(ADR 0034) |
| `GET /api/v1/admin/audit-logs` | ADMIN | 감사 로그 조회 |
| `POST /api/v1/admin/coupons` | ADMIN | 쿠폰 정의 생성 |
| `PATCH /api/v1/admin/coupons/{id}` | ADMIN | 쿠폰 정의 수정 |
| `POST /api/v1/admin/coupons/{id}/stop` | ADMIN | 쿠폰 정의 중지 |
| `GET /api/v1/admin/coupons`, `GET /api/v1/admin/coupons/{id}` | ADMIN | 쿠폰 정의 목록·상세(현황) |
| `GET /api/v1/admin/coupons/{id}/issued` | ADMIN | 발급 내역 |
| `POST /api/v1/admin/coupons/{id}/grants` | ADMIN | 직접 지급 |
| `POST /api/v1/admin/issued-coupons/{issuedCouponId}/revoke` | ADMIN | 발급 쿠폰 회수 |

### realtime

| 메서드·경로 | 설명 |
|---|---|
| `POST /api/v1/queues/{scheduleId}/entries` | 대기열 진입. 재등록은 기존 순번 유지 |
| `GET /api/v1/queues/{scheduleId}/entries/me` | 내 순번 |
| `DELETE /api/v1/queues/{scheduleId}/entries/me` | 대기열 이탈 |
| `GET /api/v1/streams/queues/{scheduleId}` | 순번 SSE (티켓) |
| `GET /api/v1/streams/schedules/{scheduleId}/seats` | 좌석 SSE (티켓). 이벤트는 [events.md §4](./events.md#4-sse-이벤트-realtime--브라우저) |

경로·권한의 원본은 각 컨트롤러와 `SecurityConfig`, Gateway 라우팅이다. 불일치가 보이면 코드를 기준으로 이 문서를 고친다.

## 8. BFF Route Handler 규칙

브라우저가 부르는 경로는 `web/src/app/api/` 아래에 있고 코어 경로와 다르다([integrations.md §8](./integrations.md#bff-route-handler)).

- 상태를 바꾸는 요청은 `Origin`(없으면 `Referer`)이 web origin과 같은지 검사한다.
- BFF가 하위 API에서 401을 받으면 access 토큰을 한 번 갱신해 재시도하고, 갱신이 실패하면 세션을 파기한다.
- 조합 화면은 **핵심 데이터**와 **부가 데이터**를 구분한다. 부가 데이터가 실패해도 핵심 데이터가 있으면 부분 응답으로 화면을 그린다.
- 입장 토큰은 브라우저가 대기열 SSE로 받아 BFF에 넘기고, BFF가 코어 호출 때 `X-Admission-Token`으로 전달한다.

## 9. API를 추가·변경할 때 체크리스트

- [ ] 경로가 `/api/v1/` 아래이고 복수형 명사 규칙을 따른다
- [ ] 권한(공개, `USER`, `ORGANIZER`)을 컨트롤러와 Gateway 양쪽에서 확인했다
- [ ] 오류는 `DomainException`으로 던지고 `code`를 정했다. 표를 갱신했다
- [ ] 상태를 바꾸는 `POST`가 재시도에 안전한지(멱등 키 필요 여부) 판단했다
- [ ] 목록이면 커서 방식이고 크기 상한이 있다
- [ ] 다른 모듈 데이터가 필요하면 [data-ownership.md](./data-ownership.md)의 방법을 따른다
- [ ] 호환되지 않는 변경이면 `v2`를 검토했고 BFF 호출부를 같이 고쳤다
