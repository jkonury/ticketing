# M5.5 쿠폰 도입 계획

- 작성일: 2026-11-17
- 상태: 완료 (보관). M5.5 전 단계를 완료했다. 결과는 [coupon-drop 측정](../../performance/m5.5/coupon-drop.md)에 있다
- 결정 근거: 2026-11-17 설계 인터뷰(Q1~Q18). 구현 0단계에서 ADR 0034~0037로 남긴다.
- 관련: [로드맵](../../roadmap.md), [아키텍처](../../architecture.md), [ADR 0017](../../adr/0017-payment-owns-payment-events.md)

## 합의한 결정

| 번호 | 항목 | 결정 |
|---|---|---|
| Q1 | 목적 | 1단계는 적용 정합성(예매–쿠폰 Saga), 2단계는 선착순 발급 경합 |
| Q2 | 로드맵 위치 | M5를 완료로 확정(300ms 두 건은 장기 과제 유지)하고 M5.5를 신설 |
| Q3 | 발행·부담 | 플랫폼이 발행하고 할인액도 플랫폼이 부담 |
| Q4 | 관리자 범위 | 승격·강등 API, `/admin` 영역, 감사 로그까지 포함 |
| Q5 | 모듈·상호작용 | 새 `coupon` 모듈과 예약 Saga, 의존 방향 `booking → coupon` |
| Q6 | 할인 규칙 | 정액·정률(최대 할인액), 최소 결제 금액, 대상 전체/특정 공연, 고정 유효 기간, 결제 최소 1원 |
| Q7 | 정산 | 판매액·수수료 정가 기준, `platformDiscountAmount` 기록, 대사는 실결제액 |
| Q8 | 사용·복원 | 예매당 1장, 회원·정의당 1장. 만료·결제 실패·시스템 취소는 복원, 확정 후 환불은 소멸 |
| Q9 | 적용 시점 | `PENDING` 예매에 별도 적용·해제·교체 API(결제 요청 전), `RESERVING` 중 결제 요청 거부 |
| Q10 | 경합 처리 | 거부 시 쿠폰 없이 `PENDING` 유지, 늦은 예약은 즉시 해제, 고아 예약 정리 Job, 늦은 사용 확정은 강제 `USED`와 불일치 기록, `(orderId, issuedCouponId)` 멱등 |
| Q11 | 금액 계약 | coupon이 할인액 계산, booking은 `list`/`discount`/`payable` 저장, 결제 비교는 `payable` |
| Q12 | 관리자 세부 | `.env` 이메일로 부트스트랩, 마지막 관리자 강등 금지, 역할 변경 시 refresh 토큰 폐기, `audit` 모듈, BFF와 코어 이중 가드 |
| Q13 | 선착순 경로 | Redis Lua(재고 + 중복 Set) 뒤 동기 DB INSERT, 유니크 제약이 최종 방어, Gateway rate limit만 사용, DB 방식 비교 실험 |
| Q14 | 1단계 발급 채널 | 관리자 직접 지급과 수량 제한 없는 받기 |
| Q15 | 정의 수명주기 | 발급 시작 후 할인 조건 수정 불가, `STOPPED`는 신규 발급만 차단, 회수는 `ISSUED`만 |
| Q16 | web 범위 | 내 쿠폰, 결제 화면 적용, 받기 페이지, 최소 수준 `/admin`. 주최자 화면은 변경 없음 |
| Q17 | 단계 구성 | M5.5-1 관리자·감사, M5.5-2 쿠폰 적용 Saga, M5.5-3 선착순 발급 |
| Q18 | M6 | coupon은 분리하지 않고, Saga 재검증 범위에 쿠폰 보상을 포함 |

따로 묻지 않고 기본값으로 둔 세부 사항: 쿠폰 알림은 로그만 남긴다. rate limit 수치와 Redis 키 상세는 M5.5-3 측정 후 정한다.

## 배경

플랫폼이 발행하고 할인액도 플랫폼이 부담하는 쿠폰을 도입한다. 학습 목표는 두 가지다.

1. 예매–쿠폰 예약 Saga로 할인 적용의 정합성을 다룬다. 보상 대상이 좌석과 쿠폰 두 가지가 된다.
2. 선착순 한정 발급의 경합을 다룬다. 초과·중복 발급은 0건이어야 한다.

로드맵에 없던 기능이므로 M5를 완료로 확정하고 M5.5를 신설한다. 후속 항목에 있던 "플랫폼 관리자 역할"은 앞당긴다.

코드에서 확인한 전제는 다음과 같다(경로는 `core/src/main/kotlin/io/github/ticketing` 기준).

- 가입 API가 요청의 `role`을 검증 없이 받는다(`member/web/AuthController.kt:50`). `ADMIN`을 enum에 추가하기 전에 가입 시 허용 역할을 USER·ORGANIZER로 제한해야 한다.
- `BookingConfirmed.amount`는 `booking.totalPrice`다(`booking/application/service/PaymentResultService.kt:83`).
- 정산 환불 금액은 `settlement.booking_sale.amount`를 JOIN해서 얻는다(`settlement/adapter/required/jooq/DailySettlementJooqAdapter.kt:49-57`).
- **의존 순환 주의**: coupon이 `BookingConfirmed`를 구독하면 `coupon → booking` 의존이 생겨 `booking → coupon`과 순환한다. ADR 0017과 같이, 사용 확정·해제 이벤트도 coupon 루트에 두고 booking이 발행한다.

## 0. 문서 선행 (코드 변경 없음)

- `docs/roadmap.md`
  - 상태 줄에 M5 완료를 확정한다(300ms 두 건은 장기 과제로 유지).
  - M5.5 절(M5.5-1/2/3 체크리스트와 완료 기준)을 추가한다.
  - 후속 항목 표에서 "플랫폼 관리자 역할"을 제거하고, "플랫폼 실수익 리포트"와 "coupon MSA 분리"를 추가한다.
  - M6 Saga 재검증 항목에 쿠폰 보상을 명시한다.
- ADR 4건(0034~0037, 0033 형식)
  - 플랫폼 관리자 역할과 `audit` 모듈
  - `coupon` 모듈과 예약 Saga
  - 정산 정가 기준과 플랫폼 할인 부담
  - 선착순 발급 방식(Redis Lua + DB 유니크, 대기열 미사용)
  - `docs/adr/README.md` 목록도 함께 갱신한다.
- `docs/architecture.md`
  - 4절: 모듈 표에 `coupon`, `audit`를 추가하고, 의존 방향(`booking → coupon`, `audit → member`, `audit → coupon`)과 이벤트 목록을 갱신한다.
  - 7절과 `docs/architecture/integrations.md`의 역할 설명에 ADMIN을 추가한다.
- `docs/architecture/flows.md`: 6.5 쿠폰 적용 Saga와 6.6 선착순 발급의 mermaid 시퀀스를 추가한다.
- 검증: `./scripts/agent/docs/check-docs.sh`

## M5.5-1 관리자·감사

**core / member**
- `member/domain/Role.kt`에 `ADMIN`을 추가한다. 가입 요청의 role은 USER·ORGANIZER만 허용하고, ADMIN이면 400을 반환한다(`AuthController`/`MemberService.signUp`).
- 부트스트랩 `AdminBootstrapRunner`
  - ApplicationRunner이고, `@ConditionalOnProperty("ticketing.admin.bootstrap-email")` 조건으로 동작한다.
  - 해당 이메일로 가입된 회원을 ADMIN으로 승격한다. 멱등이며, 회원이 없으면 경고 로그만 남긴다.
  - 이메일 값은 `.env`에서 받는다.
- 역할 변경 API `PUT /api/v1/admin/members/{id}/role`
  - `@PreAuthorize("hasRole('ADMIN')")`로 보호한다.
  - 마지막 ADMIN은 강등할 수 없다(409 `LAST_ADMIN`).
  - 변경 후 그 회원의 refresh 토큰을 전부 폐기한다. `member/service/AuthService.kt:47-69`의 전체 폐기 로직을 재사용한다.
  - 관리자 화면용 회원 목록 조회 API를 함께 추가한다.
- 이벤트 `member.MemberRoleChanged(actorId, memberId, from, to, changedAt)`

**core / audit (신규 모듈, 계층형)**
- 모듈 등록: `package-info.java`를 추가하고, 스키마 `audit`를 V11에서 만들며 `JooqCodegen.kt:16`의 SCHEMAS에 추가한다.
- 테이블 `audit.admin_action`: id, actor_id, action, target_type, target_id, detail jsonb, occurred_at, `source_event_id`. `source_event_id`에 유니크 제약을 걸어 재전달을 멱등 처리한다.
- `@ApplicationModuleListener`로 `MemberRoleChanged`를 구독한다. coupon 관리 이벤트는 M5.5-2에서 추가로 구독한다.
- 조회 API `GET /api/v1/admin/audit-logs`: keyset 페이지, 필터는 action·actor·기간.
- 모듈 검증: `core/src/test/kotlin/io/github/ticketing/ModularityTests.kt:19-23`의 모듈 집합을 갱신하고, `./gradlew :core:modulithDocs`로 `.puml`을 다시 만든다.

**web**
- `web/src/server/auth/session-store.ts:9`의 role enum에 ADMIN을 추가한다.
- `server/auth/require-admin.ts`: `require-user.ts`를 확장해 ADMIN이 아니면 403 페이지를 보여 준다. `/admin` 레이아웃에서 한 번 적용하고, 서버 액션마다 다시 검사한다(`features/organizer/actions.ts:40` 패턴).
- `proxy.ts`의 matcher에 `/admin/*`를 추가한다.
- `server/api/admin.ts`를 추가하고, 화면은 `/admin/members`(역할 변경)와 `/admin/audit-logs`를 만든다. 변경 액션은 `assertSameOrigin`을 거친다.

**테스트**
- 가입 시 ADMIN 거부
- 부트스트랩 멱등
- 마지막 관리자 강등 거부
- 역할 변경 후 기존 refresh 토큰으로 갱신 실패
- 비관리자 403
- 감사 로그 멱등
- web 가드와 Origin 거부

## M5.5-2 쿠폰 정의·발급·적용 Saga

**coupon 모듈 (헥사고날, 스키마 `coupon`)**
- 테이블
  - `coupon_definition`: id, name, discount_type(FIXED/RATE), discount_value, max_discount, min_order_amount, performance_id null, valid_from, valid_until, issue_mode(UNLIMITED/LIMITED/DIRECT), total_quantity null, status(ACTIVE/STOPPED), issue_started_at
  - `issued_coupon`: id, definition_id, member_id, status(ISSUED/RESERVED/USED/REVOKED/EXPIRED), order_id, discount_amount, reserved_until, version. **UNIQUE(definition_id, member_id)**
- 도메인 규칙
  - 발급이 시작된 뒤에는 할인 조건을 수정할 수 없다. 이름·설명 변경과 종료일 연장만 허용한다.
  - STOPPED는 신규 발급만 막는다.
  - 회수는 ISSUED 상태에만 가능하다.
  - 할인 계산은 정액 또는 정률(내림, max_discount 상한)이다. 최소 결제 금액 미만이거나 할인 후 금액이 1원 미만이면 거부한다.
- API
  - 관리자: 정의 생성·수정·중지, 발급 현황(발급·사용 수), 직접 지급(회원 ID 목록), 회수
  - 사용자: `POST /api/v1/coupons/{definitionId}/claims`(UNLIMITED. 유니크 위반 시 409 `COUPON_ALREADY_CLAIMED`, `CreateBookingService.kt:101` 패턴), `GET /api/v1/me/coupons`
- coupon 루트의 계약 이벤트(모두 coupon 소유)
  - `CouponReservationRequested(orderId, memberId, issuedCouponId, performanceId, listAmount, requestedAt)`: booking이 발행
  - `CouponReserved(orderId, issuedCouponId, discountAmount)` / `CouponRejected(orderId, issuedCouponId, reason)`: coupon이 발행
  - `CouponReleaseRequested(orderId, issuedCouponId, reason)`: booking이 발행
  - `CouponUseConfirmed(orderId, issuedCouponId, confirmedAt)`: booking이 확정 시 발행
  - 관리자 행위 이벤트(`CouponDefinitionCreated/Stopped`, `CouponRevoked`, `CouponsGranted`)는 audit가 구독한다.
- V12: 스키마 `coupon`을 만들고 `coupon_definition`, `issued_coupon`(UNIQUE(definition_id, member_id)), `use_mismatch`를 추가한다. `JooqCodegen.kt`의 SCHEMAS에도 추가한다.
- 상태 전이는 모두 조건부 UPDATE로 처리한다. `(orderId, issuedCouponId)`가 같은 재전달은 무시한다. 늦게 온 USED가 RELEASED/ISSUED 상태에 도착하면 강제로 USED로 바꾸고 불일치를 기록한다(WARN 로그와 `coupon.use_mismatch` 행).
- 고아 예약 정리 Job `releaseStaleCouponReservationsJob`
  - 위치는 `coupon/adapter/provided/batch`이고 `@Profile("batch")`로 둔다.
  - `ExpirePendingBookingsJobConfig.kt`와 `KeysetItemReader` 패턴을 따른다.
  - `reserved_until + grace`가 지난 쿠폰 중 RESERVED만 해제한다.
  - `reserved_until`은 예매 만료 + `ticketing.booking.expiry.payment-grace`보다 길게 잡는다.

**booking 변경**
- 도메인에 다음 값을 둔다.
  - `listAmount`: 기존 `totalPrice`. `total_amount` 컬럼을 그대로 쓴다.
  - `appliedCoupon: AppliedCoupon?`: issuedCouponId, state(RESERVING/RESERVED/REJECTED), discountAmount, rejectReason
  - `payableAmount = list − discount`: RESERVED일 때만 할인 반영
- V13: `booking.booking`에 `coupon_issued_id`, `coupon_state`, `discount_amount`, `coupon_reject_reason`을 추가한다.
- `PUT /api/v1/bookings/{id}/coupon` / `DELETE`
  - PENDING이고 결제 요청 전일 때만 가능하다. RESERVING 중에는 409를 반환한다.
  - 교체할 때는 이전 쿠폰에 `CouponReleaseRequested`를 보낸 뒤 새 쿠폰 예약을 요청한다.
- `RequestPaymentService`: RESERVING이면 409 `COUPON_PENDING`을 반환한다. `PaymentRequested.amount`에는 `payableAmount`를 넣는다(`:82`).
- `Booking.onPaymentSucceeded`(`Booking.kt:65`)는 `payableAmount`와 비교한다.
- 새 리스너 `CouponResultListener`
  - Reserved: `0 < discount < list`를 검증한다. 예매가 PENDING이 아니거나 이미 해제된 쿠폰이면 즉시 ReleaseRequested를 보낸다.
  - Rejected: 쿠폰 없이 PENDING을 유지하고 사유를 저장한다.
- 복원 지점: 다음 경우에 `CouponReleaseRequested`를 보낸다. 확정 후 환불은 쿠폰이 소멸하므로 보내지 않는다.
  - 만료: `ExpireBookingService`, `RequestPaymentService.expire`
  - 결제 실패: `PaymentResultService.onPaymentFailed`
  - `SEAT_ALREADY_SOLD`·`AMOUNT_MISMATCH` 취소: `PaymentResultService:90-92`, `:65`
  - 결제 전 사용자 취소: `CancelBookingService`
- 확정 시(`PaymentResultService.kt:72-86`) `CouponUseConfirmed`를 발행한다. `BookingConfirmed`에는 `amount`=payable, `listAmount`, `discountAmount`를 담는다.
- `BookingDetailResponse`(`BookingController.kt:135`)에 `listAmount, discountAmount, payableAmount, coupon{state, rejectReason}`을 추가한다.
- `BookingWarmupWork`(`:77,82`)의 금액 검증을 새 필드에 맞춘다. 쿠폰 없는 경로라 동작은 같다.

**settlement 변경 (정가 기준)**
- V14: `booking_sale`에 `list_amount`, `discount_amount`를 추가하고 기존 행은 `amount`로 채운다. `daily_settlement`에는 `platform_discount_amount`를 추가한다.
- `DailySettlementJooqAdapter.findPerformanceTotals`
  - 판매와 환불을 `list_amount`로 집계한다.
  - 판매분의 discount 합을 `platform_discount_amount`로 기록한다.
  - 환불된 예매의 할인 부담은 차감하지 않는다(쿠폰이 소멸하므로 플랫폼 비용으로 확정). 이 정책은 ADR에 명시한다.
- `DailySettlementCalculator`: 수수료는 정가 순매출 기준이므로 바뀌지 않는다. 주최자 조회 API에는 할인 부담액을 노출하지 않는다.
- 결제 대사(payment)는 바뀌지 않는다. PG 금액은 payable이다.

**web**
- 결제 화면(`features/booking/payment.tsx`)
  - 내 쿠폰 중 적용 가능한 것을 선택하거나 해제한다.
  - 적용 후 기존 폴링으로 `coupon.state`가 RESERVING에서 벗어날 때까지 기다린다.
  - 거부 사유, 할인액, 결제 금액을 표시한다.
- 마이페이지(`server/aggregate/my-page.ts`)에 내 쿠폰을 `optional()` 섹션으로 추가한다. 쿠폰 받기 페이지 `/coupons`를 만든다.
- `/admin/coupons`: 목록, 생성, 중지, 현황, 직접 지급, 회수
- BFF Route Handler와 `server/api/coupon.ts`를 추가한다. 모든 변경 요청에 Origin 검증을 적용한다.

**테스트**
- 도메인: 할인 계산 경계(최대 할인액, 1원 하한, 최소 결제 금액), 상태 전이, 수정 불가 규칙
- Saga 통합
  - 적용 후 확정되면 USED
  - 결제 실패·만료·좌석 충돌이면 복원
  - 확정 후 환불이면 소멸
  - 만료 후 늦게 도착한 Reserved는 즉시 해제
  - 이벤트 재전달 멱등
  - 늦은 USED 강제 반영
- 정리 Job: 재시작, 확정과의 경합
- 정산 Job: 쿠폰을 쓴 예매의 판매액·수수료가 정가 기준이고 할인 부담액이 기록된다.
- E2E `CouponFlowE2eTests.kt`(`BookingFlowE2eTests.kt` 패턴): USED, 복원, 소멸 세 경로. 복원은 결제 전 사용자 취소로 검증한다(fake-pg로 결제 실패를 유도할 수 없어서. 결제 실패 복원은 Saga 통합 테스트가 다룬다). E2E 관리자 계정은 `scripts/e2e/run.sh`가 가입 후 DB에서 승격해 준비한다
- Playwright: 결제 화면 쿠폰 적용

## M5.5-3 선착순 발급

- Redis 초기화: LIMITED 정의를 열 때 `coupon:stock:{defId}`(남은 수량)와 `coupon:claimed:{defId}`(Set)를 만든다. 두 키는 해시 태그로 같은 슬롯에 둔다.
- core/src/main/resources/redis/coupon/claim.lua (신규)(`redis/booking/*.lua`와 `RedisSeatHoldAdapter.kt` 패턴)
  - Set에 이미 있으면 DUPLICATE, 재고가 0이면 SOLD_OUT, 둘 다 아니면 DECR과 SADD를 수행한다.
  - 성공하면 동기로 `issued_coupon`에 INSERT한다.
  - 유니크 위반이나 DB 실패 시 `unclaim.lua`로 보상한다(INCR, SREM).
- Redis 유실·불일치 보정: DB 기준으로 재고 = total − 발급 수, Set = 발급 회원으로 맞춘다. 보정 Job이나 오픈 시 재구성으로 처리한다(좌석 스냅샷 보정 Job 패턴).
- gateway
  - `GatewayRoutes.kt`에 `core-coupon-claims`(`POST /api/v1/coupons/*/claims`) 라우트를 추가한다.
  - `RateLimitFilter.kt`에 `coupon-claim` 버킷(보충 1, 버스트 3)을 추가한다. 임시값으로 확정했고, 이번 시나리오는 회원당 1회라 429가 0건이어서 수치 검증은 후속 테스트로 둔다.
  - core 호스트로 가는 연결 풀에만 상한 100을 둔다(`forRemoteHost`). 기본 풀(elastic)은 동시 받기마다 core로 새 연결을 만들어 core accept 큐가 넘쳤고, 전역 상한은 realtime SSE까지 막아 hot-seats가 깨졌다.
  - 대기열과 입장 토큰은 쓰지 않는다.
- 부하 테스트
  - 실제 측정은 Gatling `CouponDropSimulation`으로 했다(`run.sh --scenario coupon-drop --tool gatling`). 1만 명이 100장을 노리며, 새 연결 1만 개가 동시에 몰려 listen 큐가 넘치는 문제를 피하려고 20초 ramp 동안 `GET /api/v1/coupons`로 연결을 사전 수립하고 40초 뒤 같은 시각에 받는다. k6 `loadtest/scenarios/coupon-drop.ts`는 남아 있으나 측정에는 쓰지 않았다.
  - `scripts/loadtest/run.sh:90`의 case와 `seed.sh coupon`을 추가한다.
  - verify-coupon-integrity.sh (신규, scripts/loadtest): 발급 수 ≤ total, `(def, member)` 중복 0, Redis 재고 + 발급 수 = total, Set = DB 회원 집합을 확인한다.
- 비교 실험: 같은 시나리오를 DB 조건부 UPDATE(`remaining > 0`) 방식으로 측정한다(프로퍼티로 전환). 결과는 [docs/performance/m5.5/coupon-drop.md](../../performance/m5.5/coupon-drop.md)에 기록했다.
- 회귀: hot-seats 중복 판매 0건 유지(`verify-integrity.sh`)

## 검증 (하위 단계마다)

- `./scripts/agent/test/run-tests.sh` → `./scripts/agent/test/check-summary.sh`(ktlintCheck, detekt 포함, 순차 실행)
- 모듈 의존이 바뀌므로 `ModularityTests` 통과와 `./gradlew :core:modulithDocs`
- web: `./scripts/agent/test/web-check.sh --build`
- E2E: `./scripts/e2e/run.sh`(M5.5-2부터 `--web` 포함)
- 부하: `./scripts/loadtest/run.sh --scenario coupon-drop`, `--scenario hot-seats`(다른 Gradle 작업이 없을 때 단독 실행)
- 문서: `./scripts/agent/docs/check-docs.sh`. 하위 단계가 끝날 때마다 roadmap 체크박스와 architecture를 갱신한다.

## 완료 기준

1. 예매–쿠폰 E2E 세 경로(USED, 복원, 소멸)가 통과한다.
2. 쿠폰을 쓴 예매의 정산이 정가 기준과 일치한다.
3. coupon-drop에서 초과·중복 발급 0건이고, Redis–DB 대사가 일치한다.
4. hot-seats 중복 판매 0건을 유지한다.
