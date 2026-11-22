# 이벤트 카탈로그 (§4)

> [← 아키텍처 개요](../architecture.md) · 범위: 코어 모듈 간 Modulith 이벤트, 앱 간 Redis Streams, SSE 이벤트
> 함께 보기: [flows.md](./flows.md), [redis.md](./redis.md), [data-ownership.md](./data-ownership.md), [ADR 0010](../adr/0010-events-modulith-and-redis-streams.md), [ADR 0017](../adr/0017-payment-owns-payment-events.md)

이벤트는 세 종류다. 통신 범위와 전달 보장이 서로 다르므로 구분해서 본다.

| 종류 | 범위 | 수단 | 전달 보장 |
|---|---|---|---|
| 도메인 이벤트 | 코어 모듈 사이, core ↔ payment 앱 | Modulith 이벤트 + 발행 레지스트리 (결제 계약 5종은 Kafka 외부화) | 최소 1회. 중복 전달 가능 |
| 좌석 변경 스트림 | 코어 → realtime | Redis Streams | 최근 약 10,000건 재생 가능 |
| SSE 이벤트 | realtime → 브라우저 | Server-Sent Events | 재연결 시 재생 |

## 1. 공통 규칙

- 이벤트 클래스는 **발행 모듈의 루트 패키지**에 둔다. 예외로 결제 계약 이벤트 5종은 `payment` 루트 패키지(구현은 `contract` 모듈)에 둔다([ADR 0017](../adr/0017-payment-owns-payment-events.md)).
- 페이로드에는 ID와 단순 값만 담는다. 엔티티나 다른 모듈의 내부 타입을 싣지 않는다. Kafka 외부화 대상도 그대로 직렬화할 수 있어야 한다.
- 금액은 `BigDecimal`(원 단위), 시각은 `Instant`다.
- **계약 이벤트**(결제 5종, `BookingConfirmed`, `BookingCancelled`)는 `contract` 모듈에 두고 패키지는 위 규칙대로 유지한다([ADR 0042](../adr/0042-contract-module-keeps-packages-and-json-compat-rules.md)). JSON 규칙:
  - 필드 이름은 Kotlin 프로퍼티 이름 그대로(camelCase)다. 금액(`BigDecimal`)은 JSON 숫자, 시각(`Instant`)은 ISO-8601 UTC 문자열이다. ID는 숫자, 목록은 배열이다.
  - 버전 필드는 두지 않는다. 필드 **추가만** 허용하며 새 필드는 기본값을 가진다(구 페이로드를 읽을 수 있어야 한다). 소비자는 모르는 필드를 무시한다.
  - 필드 삭제·이름·타입·의미 변경은 금지한다. 필요하면 새 이벤트 타입(`…V2`)을 추가하고 한동안 둘을 함께 발행한다.
  - 예: `PaymentSucceeded` = `{"paymentId":11,"orderId":101,"amount":50000,"paidAt":"2026-01-02T03:04:05Z"}`. 전체 예시는 `contract/src/testFixtures/resources/contract-events/`이며 `ContractEventSerializationTests`가 형식을 강제한다.
- 구독은 `adapter.provided.event`에 둔다. 결제 계약 5종은 `@KafkaListener`, 나머지 로컬 이벤트는 `@ApplicationModuleListener`로 처리한다.
- **리스너는 멱등이어야 한다.** 이벤트 발행 레지스트리(`public.event_publication`)는 완료되지 않은 이벤트를 다시 전달한다. 같은 이벤트가 두 번 와도 결과가 같아야 한다.
- 이벤트를 바꿀 때는 호환성을 먼저 본다. 필드를 지우거나 의미를 바꾸면 구독 모듈이 깨지므로 필드는 추가를 기본으로 한다.
- 새 이벤트를 추가하거나 의존 방향이 바뀌면 이 문서와 [architecture.md §4](../architecture.md#이벤트-목록)를 함께 고치고 Modulith 구조 검증을 돌린다. `./gradlew :core:modulithDocs`로 다이어그램을 다시 생성한다.

## 2. 도메인 이벤트 (Modulith)

| 이벤트 | 클래스 위치 | 발행 | 구독 | 발행 시점 |
|---|---|---|---|---|
| `ScheduleRegistered` | `catalog` | catalog | booking | 회차 등록 |
| `PaymentRequested` | `payment` | booking(core) | payment(앱) | 예매의 결제 요청 |
| `RefundRequested` | `payment` | booking(core) | payment(앱) | 사용자 취소, 만료·취소된 예매나 좌석 충돌 등으로 환불이 필요한 결제 성공 |
| `PaymentSucceeded` | `payment` | payment(앱) | booking(core) | PG 승인 웹훅(`PAYMENT_APPROVED`) 처리 |
| `PaymentFailed` | `payment` | payment(앱) | booking(core) | PG 실패 웹훅(`PAYMENT_FAILED`) 처리 또는 PG 접수 거절 |
| `PaymentRefunded` | `payment` | payment(앱) | booking(core) | PG 환불 완료 웹훅(`REFUND_COMPLETED`) 처리 |
| `BookingConfirmed` | `booking` | booking | notification, settlement | 결제 성공으로 예매 확정(정가·할인액 포함) |
| `BookingCancelled` | `booking` | booking | notification, settlement | 예매 취소 |
| `BookingExpired` | `booking` | booking | notification | 미결제 예매 만료 |
| `CouponReservationRequested` | `coupon` | booking | coupon | 쿠폰 적용 시 예약 요청 |
| `CouponReserved` | `coupon` | coupon | booking | 쿠폰 예약 성공 |
| `CouponRejected` | `coupon` | coupon | booking | 쿠폰 예약 거부. 사유 `NOT_FOUND`, `NOT_OWNER`, `NOT_AVAILABLE`와 할인 정책 위반(`OUT_OF_VALID_PERIOD`, `PERFORMANCE_MISMATCH`, `BELOW_MIN_ORDER_AMOUNT`, `AMOUNT_BELOW_MINIMUM`) |
| `CouponReleaseRequested` | `coupon` | booking | coupon | 쿠폰 제거·교체 또는 예매 보상 시 해당 예약 시도 해제 |
| `CouponUseConfirmed` | `coupon` | booking | coupon | 예매 확정 시 쿠폰 사용 확정 |
| `MemberRoleChanged` | `member` | member | audit | 관리자 역할 변경 |
| `CouponDefinitionCreated`, `CouponDefinitionUpdated`, `CouponDefinitionStopped`, `CouponsGranted`, `CouponRevoked` | `coupon` | coupon | audit | 쿠폰 관리자 행위 |

페이로드는 [architecture.md §4 이벤트 목록](../architecture.md#이벤트-목록)이 기준이다. 쿠폰 Saga 이벤트 5종은 `reservationAttemptId`를 전달한다. 구형 JSON에서 누락 시 `0`으로 읽는다. [ADR 0045](../adr/0045-coupon-reservation-attempts.md)

### 구독 처리 규칙

| 구독 클래스 | 이벤트 | 처리 | 멱등 근거 |
|---|---|---|---|
| booking `ScheduleRegisteredListener` | `ScheduleRegistered` | `booking.schedule`, `booking.schedule_seat`에 사본 적재, 커밋 후 Redis 스냅샷 초기화 | `saveIfAbsent`로 이미 있으면 건너뜀. 스냅샷은 `HSETNX` |
| payment 앱 `PaymentRequestListener` | `PaymentRequested` | 결제 기록 생성 후 PG에 결제 요청(트랜잭션 밖) | `orderId`·멱등 키로 기존 결제를 찾아 재사용. `REQUESTED`이고 PG 거래 ID가 없을 때만 재요청 |
| payment 앱 `RefundRequestListener` | `RefundRequested` | 환불 전이 후 PG에 환불 요청(트랜잭션 밖) | 이미 `REFUNDED`면 건너뜀 |
| booking `PaymentResultListener` | `PaymentSucceeded`, `PaymentFailed`, `PaymentRefunded` | 예매 확정·실패 처리. `PaymentRefunded`는 로그만 | 예매 상태 전이 검사. 이미 `CONFIRMED`면 `AlreadyConfirmed`로 무시하고, `EXPIRED`·`CANCELLED`면 환불 경로로 보낸다 |
| settlement `BookingEventListener` | `BookingConfirmed`, `BookingCancelled` | `BookingConfirmed`는 `booking_sale`에 적재. `BookingCancelled`는 `refundRequired=true`일 때만 `booking_refund`에 적재 | `ON CONFLICT (booking_id) DO NOTHING` |
| coupon `CouponReservationListener` | `CouponReservationRequested`, `CouponReleaseRequested`, `CouponUseConfirmed` | 발급 쿠폰 상태를 조건부 UPDATE로 전이(예약·해제·사용 확정). 예약 결과는 `CouponReserved`·`CouponRejected`로 응답 | 발급 행 잠금과 주문별 최신 시도 기록을 사용한다. 해제·사용 확정은 주문과 시도가 일치해야 하며, 정리 후 복구 가능한 사용 확정은 `coupon.use_mismatch`에 기록한다 |
| booking `CouponResultListener` | `CouponReserved`, `CouponRejected` | `CouponReserved`는 예매의 `coupon_state`·할인액에 반영한다. 예매가 없거나 교체·해제·종료 뒤 늦게 온 응답, 무효 할인액이면 해당 시도의 해제를 요청한다. `CouponRejected`는 쿠폰 없이 진행하고 사유를 저장한다 | 제거된 시도의 거절은 무시하고 성공은 해당 시도로 해제를 요청한다. 이미 반영한 같은 시도의 성공은 무시한다. 낙관적 락 충돌로 실패한 발행은 미완료로 남아 core 재시작 시 재발행 대상이 된다 |
| audit `MemberAuditListener`, `CouponAuditListener` | `MemberRoleChanged`, 쿠폰 관리자 행위 5종 | `audit.admin_action`에 기록 | `source_event_id` 유니크 |
| notification `BookingNotificationListener` | `BookingConfirmed`, `BookingCancelled` (`BookingExpired`) | 로그 출력 | 없음. 재전달 시 로그가 중복될 수 있음 |

상태 전이와 환불 조건은 [flows.md §6.3, §6.4](./flows.md#63-예매-상태-전이)를 본다.

### Kafka 외부화 대상 (M6)

프로세스 경계를 넘는 계약 이벤트 7종이 대상이다([ADR 0039](../adr/0039-kafka-externalizes-modulith-events-only.md), [M6 계획](../plans/m6-msa.md)). 2단계에서 결제 5종을 외부화했고, 3단계에서 payment가 별도 앱으로 분리돼 이 5종이 core와 payment 앱 사이를 Kafka로만 오간다. booking 2종은 4단계에 예정되어 있다. 토픽은 이벤트별이다. core-batch·payment-batch는 발행 가능하지만 리스너를 기동하지 않는다.

| 이벤트 | 발행 (현재 → 분리 후. 분리를 마친 이벤트는 현재만) | 구독 (현재 → 분리 후. 같은 규칙) | 외부화 단계 | 토픽 | 메시지 키 | 파티션 | 소비자 멱등 근거 | 비고 |
|---|---|---|---|---|---|---|---|---|
| `PaymentRequested` | booking(core) | payment(앱) | 2단계 외부화, 3단계 앱 분리 | `ticketing.payment.payment-requested` | `orderId` | 3 | `orderId`·멱등 키로 기존 결제 재사용, `REQUESTED`이고 PG 거래 ID가 없을 때만 PG 재요청 | 결제 DB의 `order_id`·`idempotency_key` 유니크 제약이 최종 방어선 |
| `RefundRequested` | booking(core) | payment(앱) | 2단계 외부화, 3단계 앱 분리 | `ticketing.payment.refund-requested` | `orderId` | 3 | 이미 `REFUNDED`면 건너뜀. 환불 진행 중 중복 도착 경합을 테스트로 검증 | |
| `PaymentSucceeded` | payment(앱) | booking(core) | 2단계 외부화, 3단계 앱 분리 | `ticketing.payment.payment-succeeded` | `orderId` | 3 | 예매 상태 전이 검사(이미 `CONFIRMED`면 `AlreadyConfirmed` 무시, `EXPIRED`·`CANCELLED`면 환불 경로) | |
| `PaymentFailed` | payment(앱) | booking(core) | 2단계 외부화, 3단계 앱 분리 | `ticketing.payment.payment-failed` | `orderId` | 3 | 예매 상태 전이 검사 | 토픽이 달라 같은 주문의 성공·실패·환불 간 순서는 보장되지 않는다. 상태 전이 검사에 의존 |
| `PaymentRefunded` | payment(앱) | booking(core) | 2단계 외부화, 3단계 앱 분리 | `ticketing.payment.payment-refunded` | `orderId` | 3 | 로그만 남김(부작용 없음) | |
| `BookingConfirmed` | booking(core) → booking(core) | notification(core), settlement(core) → notification(core), settlement 앱 | 4단계 예정(settlement 분리 시) | `ticketing.booking.booking-confirmed` | `bookingId` | 3 | settlement: `ON CONFLICT (booking_id) DO NOTHING`. notification: 없음(로그만, 중복 가능) | notification은 core에 남으므로 **같은 JVM 로컬 리스너를 유지**한다. settlement만 Kafka 소비로 바꾼다 |
| `BookingCancelled` | booking(core) → booking(core) | notification(core), settlement(core) → notification(core), settlement 앱 | 4단계 예정 | `ticketing.booking.booking-cancelled` | `bookingId` | 3 | settlement: `refundRequired=true`일 때만 `ON CONFLICT (booking_id) DO NOTHING`. notification: 없음 | 위와 같다 |
| 외부화 안 함 | | | | | | | | `ScheduleRegistered`(catalog→booking), 쿠폰 계약 이벤트 5종(booking↔coupon), `BookingExpired`, audit 이벤트(`MemberRoleChanged`, 쿠폰 관리자 행위 5종): 분리 후에도 core 한 JVM 안에서 소비한다. Modulith 이벤트 그대로 둔다 |

`orderId`와 `bookingId`는 같은 값(예매 ID)이다. 같은 토픽 안에서는 같은 키가 같은 파티션으로 가 순서가 보장되지만, 토픽이 다르면 순서가 보장되지 않는다. 그래서 같은 예매의 이벤트 간 순서 의존은 소비자의 상태 전이 검사로 막는다.

**규칙**

- 토픽 이름: `ticketing.<이벤트 클래스가 속한 도메인>.<이벤트 이름 kebab-case>`. 도메인은 클래스의 루트 패키지이며, 결제 계약 이벤트 5종은 발행 모듈과 무관하게 `payment`다([ADR 0017](../adr/0017-payment-owns-payment-events.md)). 접두어는 `ticketing.kafka.topic-prefix`로 설정하며 기본값 `ticketing`은 한 브로커를 다른 용도와 나눠 쓸 때 충돌을 피한다.
- 메시지 키: 같은 예매의 이벤트가 한 파티션에서 순서대로 처리되도록 `orderId`(예정된 `booking*`은 `bookingId`). Spring에 의존하지 않는 계약 클래스에 애노테이션을 붙이지 않고 core의 `BookingEventExternalizationConfig`(요청 2종)와 payment 앱의 `PaymentEventExternalizationConfig`(결과 3종)가 토픽과 문자열 키를 지정한다.
- 파티션 수: 결제 원본 토픽당 3, DLT당 1, 복제본 1이다. [2-5 측정](../performance/m6/02-kafka-externalization-gatling.md) 후 현 설정을 유지했다. 다른 설정과의 비교 실험은 하지 않았다.
- 페이로드는 §1의 JSON 규칙(골든 파일)을 따른다. 토픽 이름 변경은 소비자를 깨뜨리므로 필드 삭제와 같은 취급이다.

**같은 JVM 리스너와 외부화**

- Modulith 외부화는 `EventExternalizerModuleListener`라는 **추가 `@ApplicationModuleListener`**로 구현된다([소스](https://github.com/spring-projects/spring-modulith/blob/main/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/support/EventExternalizerModuleListener.java), 확인한 것은 main 브랜치이며 프로젝트는 2.1.1). 이벤트를 소비하지 않으므로 외부화한다고 로컬 `@ApplicationModuleListener`가 막히지 않는다. 발행 레지스트리는 리스너마다 행을 하나씩 만든다. 공식 레퍼런스에는 이 동작이 명시되어 있지 않고 소스로 확인한 사실이다.
- 2-3에서 payment 요청 리스너 2종과 booking 결과 리스너 3종을 `@KafkaListener`로 교체하고 해당 로컬 리스너를 제거했다. 같은 이벤트의 이중 처리를 피한다. 3단계에서 payment 리스너는 payment 앱으로 옮겼고 소비자 그룹 `ticketing-payment`를 유지해 core가 커밋한 오프셋을 이어받는다.
- payment 앱은 자기 DB(`payment_db`)에 `event_publication`을 따로 둔다. 레지스트리를 core와 공유하지 않으므로 미완료 이벤트 확인과 재제출도 앱별로 한다.
- 외부화는 `event_publication`을 거치므로 브로커 전송 실패는 외부화 리스너 행만 미완료로 남고 로컬 리스너 행에는 영향이 없다.

근거: [Spring Modulith 이벤트 외부화](https://docs.spring.io/spring-modulith/reference/events.html#externalization)(`@Externalized` 문법 `토픽::키`는 `#externalization.annotations`, 프로그래매틱 설정은 `#externalization.api`, 순서는 `#externalization.serialization`). Kafka 모듈은 라우팅 키를 토픽·메시지 키로 쓴다(`#externalization.infrastructure`). 본문은 타입 헤더 없는 JSON 바이트이며 소비자는 리스너 파라미터 타입으로 역직렬화한다. 소비자 그룹은 payment 앱의 `ticketing-payment`와 core의 `ticketing-booking`이다. 재시도 소진 시 DLT로 보낸다. 장애 복구와 DLT 재처리는 [런북 §3.7](../runbook.md#37-미완료-이벤트-발행이-쌓인다)을 따른다.

### 의존 방향

`booking → catalog`, `booking → payment`(core의 `payment` 패키지는 계약 이벤트만 담는다), `booking → coupon`, `notification → booking`, `settlement → booking`, `audit → member`, `audit → coupon`. 반대 방향 의존을 만들면 `ApplicationModules.verify()`가 실패한다. payment 앱은 예매를 모르고 `orderId`(=예매 ID)만 안다.

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
