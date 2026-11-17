# 이벤트 카탈로그 (§4)

> [← 아키텍처 개요](../architecture.md) · 범위: 코어 모듈 간 Modulith 이벤트, 앱 간 Redis Streams, SSE 이벤트
> 함께 보기: [flows.md](./flows.md), [redis.md](./redis.md), [data-ownership.md](./data-ownership.md), [ADR 0010](../adr/0010-events-modulith-and-redis-streams.md), [ADR 0017](../adr/0017-payment-owns-payment-events.md)

이벤트는 세 종류다. 통신 범위와 전달 보장이 서로 다르므로 구분해서 본다.

| 종류 | 범위 | 수단 | 전달 보장 |
|---|---|---|---|
| 도메인 이벤트 | 코어 모듈 사이 | Modulith 이벤트 + 발행 레지스트리 | 최소 1회. 중복 전달 가능 |
| 좌석 변경 스트림 | 코어 → realtime | Redis Streams | 최근 약 10,000건 재생 가능 |
| SSE 이벤트 | realtime → 브라우저 | Server-Sent Events | 재연결 시 재생 |

## 1. 공통 규칙

- 이벤트 클래스는 **발행 모듈의 루트 패키지**에 둔다. 예외로 결제 계약 이벤트 5종은 `payment` 루트에 둔다([ADR 0017](../adr/0017-payment-owns-payment-events.md)).
- 페이로드에는 ID와 단순 값만 담는다. 엔티티나 다른 모듈의 내부 타입을 싣지 않는다. 추후 Kafka로 외부화할 때 그대로 직렬화할 수 있어야 한다.
- 금액은 `BigDecimal`(원 단위), 시각은 `Instant`다.
- 구독은 `adapter.provided.event`의 `@ApplicationModuleListener`로만 한다.
- **리스너는 멱등이어야 한다.** 이벤트 발행 레지스트리(`public.event_publication`)는 완료되지 않은 이벤트를 다시 전달한다. 같은 이벤트가 두 번 와도 결과가 같아야 한다.
- 이벤트를 바꿀 때는 호환성을 먼저 본다. 필드를 지우거나 의미를 바꾸면 구독 모듈이 깨지므로 필드는 추가를 기본으로 한다.
- 새 이벤트를 추가하거나 의존 방향이 바뀌면 이 문서와 [architecture.md §4](../architecture.md#이벤트-목록)를 함께 고치고 Modulith 구조 검증을 돌린다. `./gradlew :core:modulithDocs`로 다이어그램을 다시 생성한다.

## 2. 도메인 이벤트 (Modulith)

| 이벤트 | 클래스 위치 | 발행 | 구독 | 발행 시점 |
|---|---|---|---|---|
| `ScheduleRegistered` | `catalog` | catalog | booking | 회차 등록 |
| `PaymentRequested` | `payment` | booking | payment | 예매의 결제 요청 |
| `RefundRequested` | `payment` | booking | payment | 사용자 취소, 만료·취소된 예매나 좌석 충돌 등으로 환불이 필요한 결제 성공 |
| `PaymentSucceeded` | `payment` | payment | booking | PG 승인 웹훅(`PAYMENT_APPROVED`) 처리 |
| `PaymentFailed` | `payment` | payment | booking | PG 실패 웹훅(`PAYMENT_FAILED`) 처리 또는 PG 접수 거절 |
| `PaymentRefunded` | `payment` | payment | booking | PG 환불 완료 웹훅(`REFUND_COMPLETED`) 처리 |
| `BookingConfirmed` | `booking` | booking | notification, settlement | 결제 성공으로 예매 확정(정가·할인액 포함) |
| `BookingCancelled` | `booking` | booking | notification, settlement | 예매 취소 |
| `BookingExpired` | `booking` | booking | notification | 미결제 예매 만료 |
| `CouponReservationRequested` | `coupon` | booking | coupon | 결제 요청 시 쿠폰 적용 예약 요청 |
| `CouponReserved` | `coupon` | coupon | booking | 쿠폰 예약 성공 |
| `CouponRejected` | `coupon` | coupon | booking | 쿠폰 예약 거부. 사유 `NOT_FOUND`, `NOT_OWNER`, `NOT_AVAILABLE`와 할인 정책 위반(`OUT_OF_VALID_PERIOD`, `PERFORMANCE_MISMATCH`, `BELOW_MIN_ORDER_AMOUNT`, `AMOUNT_BELOW_MINIMUM`) |
| `CouponReleaseRequested` | `coupon` | booking | coupon | 예매 복원 지점에서 쿠폰 예약 해제 |
| `CouponUseConfirmed` | `coupon` | booking | coupon | 예매 확정 시 쿠폰 사용 확정 |
| `MemberRoleChanged` | `member` | member | audit | 관리자 역할 변경 |
| `CouponDefinitionCreated`, `CouponDefinitionUpdated`, `CouponDefinitionStopped`, `CouponsGranted`, `CouponRevoked` | `coupon` | coupon | audit | 쿠폰 관리자 행위 |

페이로드는 [architecture.md §4 이벤트 목록](../architecture.md#이벤트-목록)이 기준이다. 이 문서에는 구독 측 처리 규칙을 정리한다.

### 구독 처리 규칙

| 구독 클래스 | 이벤트 | 처리 | 멱등 근거 |
|---|---|---|---|
| booking `ScheduleRegisteredListener` | `ScheduleRegistered` | `booking.schedule`, `booking.schedule_seat`에 사본 적재, 커밋 후 Redis 스냅샷 초기화 | `saveIfAbsent`로 이미 있으면 건너뜀. 스냅샷은 `HSETNX` |
| payment `PaymentRequestListener` | `PaymentRequested` | 결제 기록 생성 후 PG에 결제 요청(트랜잭션 밖) | `orderId`·멱등 키로 기존 결제를 찾아 재사용. `REQUESTED`이고 PG 거래 ID가 없을 때만 재요청 |
| payment `RefundRequestListener` | `RefundRequested` | 환불 전이 후 PG에 환불 요청(트랜잭션 밖) | 이미 `REFUNDED`면 건너뜀 |
| booking `PaymentResultListener` | `PaymentSucceeded`, `PaymentFailed`, `PaymentRefunded` | 예매 확정·실패 처리. `PaymentRefunded`는 로그만 | 예매 상태 전이 검사. 이미 `CONFIRMED`면 `AlreadyConfirmed`로 무시하고, `EXPIRED`·`CANCELLED`면 환불 경로로 보낸다 |
| settlement `BookingEventListener` | `BookingConfirmed`, `BookingCancelled` | `BookingConfirmed`는 `booking_sale`에 적재. `BookingCancelled`는 `refundRequired=true`일 때만 `booking_refund`에 적재 | `ON CONFLICT (booking_id) DO NOTHING` |
| coupon `CouponReservationListener` | `CouponReservationRequested`, `CouponReleaseRequested`, `CouponUseConfirmed` | 발급 쿠폰 상태를 조건부 UPDATE로 전이(예약·해제·사용 확정). 예약 결과는 `CouponReserved`·`CouponRejected`로 응답 | 조건부 UPDATE와 `order_id` 일치 검사. 늦은 사용 확정은 강제 반영하고 `coupon.use_mismatch`에 기록 |
| booking `CouponResultListener` | `CouponReserved`, `CouponRejected` | `CouponReserved`는 예매의 `coupon_state`·할인액에 반영한다. 예매가 없거나 교체·해제·종료 뒤 늦게 온 응답, 무효 할인액이면 `CouponReleaseRequested`로 즉시 해제를 요청한다. `CouponRejected`는 쿠폰 없이 진행하고 사유를 저장한다 | 이미 반영한 같은 예약은 무시. 낙관적 락 충돌은 던져 이벤트 발행 레지스트리가 재시도 |
| audit `MemberAuditListener`, `CouponAuditListener` | `MemberRoleChanged`, 쿠폰 관리자 행위 5종 | `audit.admin_action`에 기록 | `source_event_id` 유니크 |
| notification `BookingNotificationListener` | `BookingConfirmed`, `BookingCancelled` (`BookingExpired`) | 로그 출력 | 없음. 재전달 시 로그가 중복될 수 있음 |

상태 전이와 환불 조건은 [flows.md §6.3, §6.4](./flows.md#63-예매-상태-전이)를 본다.

### 의존 방향

`booking → catalog`, `booking → payment`, `booking → coupon`, `notification → booking`, `settlement → booking`, `audit → member`, `audit → coupon`. 반대 방향 의존을 만들면 `ApplicationModules.verify()`가 실패한다. `payment`는 예매를 모르고 `orderId`(=예매 ID)만 안다.

## 3. 좌석 변경 스트림 (Redis Streams)

코어 `booking`이 좌석 상태가 바뀔 때마다 기록하고 realtime이 구독한다. realtime은 PostgreSQL을 읽지 않으므로 이 스트림과 스냅샷이 좌석 정보의 유일한 출처다.

| 키 | 타입 | 기록 | 구독 |
|---|---|---|---|
| `booking:seat-events:{<scheduleId>}` | Stream, `MAXLEN ~ 10000` | booking | realtime |
| `booking:seats:{<scheduleId>}` | Hash (스냅샷) | booking | realtime |

엔트리 필드:

| 필드 | 의미 |
|---|---|
| `seatIds` | 쉼표로 구분한 좌석 ID |
| `status` | `AVAILABLE`, `HELD`, `SOLD` |
| `holdExpiresAt` | `HELD`일 때 만료 epoch ms |
| `at` | 변경 epoch ms |

- 스냅샷 반영은 DB 커밋 뒤에 한다. 반영이 실패하면 스냅샷이 어긋날 수 있고 `reconcileSeatSnapshotJob`이 DB 기준으로 바로잡는다.
- 선점 가능 여부의 기준은 스냅샷이 아니라 선점 키와 `SOLD`다.
- 키 형식은 [redis.md](./redis.md)를 본다.

## 4. SSE 이벤트 (realtime → 브라우저)

브라우저는 BFF를 거치지 않고 단기 SSE 티켓으로 Gateway를 통해 연결한다([integrations.md §7](./integrations.md#7-인증인가)).

### 대기열 `GET /api/v1/streams/queues/{scheduleId}`

| 이벤트 | 데이터 | 의미 |
|---|---|---|
| `position` | `{position}` | 현재 순번 |
| `admitted` | 입장 토큰 정보 | 입장 허가. 브라우저가 토큰을 BFF에 넘긴다 |
| `removed` | 없음 | 대기열에서 제거됨 |

### 좌석 `GET /api/v1/streams/schedules/{scheduleId}/seats`

| 이벤트 | 데이터 | `id` |
|---|---|---|
| `snapshot` | `{seats: [{seatId, status, holdExpiresAt?}]}` | 스냅샷 직전 Stream 마지막 ID |
| `seat` | `{seatIds, status, holdExpiresAt, at}` | Stream 엔트리 ID |

- 접속 시 `snapshot`을 먼저 보내고 이후 `seat` 변경을 이어 보낸다.
- 재연결 시 `Last-Event-ID` 헤더를 우선 쓰고, 없으면 `?lastEventId=` 쿼리를 쓴다. Stream에 그 ID가 남아 있으면 다음 엔트리부터 재생하고, 없으면 `snapshot`부터 다시 보낸다([ADR 0021](../adr/0021-realtime-queue-and-sse-contracts.md), [ADR 0022](../adr/0022-bff-session-and-aggregation-apis.md)).
- SSE 티켓은 1회용이라 재연결마다 새 티켓이 필요하다.

## 5. 이벤트를 추가할 때 체크리스트

- [ ] 발행 모듈과 구독 모듈, 의존 방향이 기존 규칙(모듈 순환 없음)을 지킨다
- [ ] 페이로드가 ID와 단순 값뿐이다
- [ ] 구독자가 중복 전달에 멱등하다. 테스트에서 같은 이벤트를 두 번 주입한다
- [ ] 이 문서와 [architecture.md §4](../architecture.md#이벤트-목록)를 갱신했다
- [ ] Modulith 구조 검증을 통과했고 `docs/modulith/*.puml`이 필요하면 갱신됐다
