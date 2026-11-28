---
status: approved
approved_at: 2026-11-28T20:21:06+09:00
---

# 관리자 예매 강제 취소와 공연 취소 Saga

## 목표와 근거

관리자가 문제 예매를 강제 취소(확정 예매는 환불)하고, 공연을 되돌릴 수 없게 취소해 딸린 예매를 일괄 정리할 수 있게 한다. 로드맵 [우선 처리 순서](../../roadmap.md#우선-처리-순서-성능-항목-제외-2026-11-28-정리) 1번 "관리자 기능 후속"의 첫 묶음이다. 결정은 [ADR 0054](../../adr/0054-admin-force-cancel-and-performance-cancel.md), 선행 구조는 [ADR 0052](../../adr/0052-admin-intervention-states.md)와 [관리자 기능 강화 계획](2026-11-28-admin-enhancement.md)(완료)을 따른다.

끝났을 때 관찰할 결과:

- 관리자가 `/admin/bookings/{id}`에서 사유를 입력해 PENDING·CONFIRMED 예매를 강제 취소한다. 확정 예매는 결제가 `REFUNDED`가 되고 좌석이 다시 판매 가능해진다.
- 관리자가 `/admin/performances`에서 공연을 취소하면 공개 조회에서 사라지고 신규 예매·결제 요청이 거부된다. 딸린 PENDING·CONFIRMED 예매는 모두 `CANCELLED(PERFORMANCE_CANCELLED)`가 되고 확정분은 환불된다. 취소된 공연은 재개할 수 없다.
- 취소 반영 뒤 결제 성공이 도착한 예매는 확정되지 않고 자동 환불된다.
- 강제 취소와 공연 취소가 감사 로그에 사유와 함께 남는다. 정산에는 환불이 기록된다.

## 범위

- 포함: core(booking·catalog·audit)의 강제 취소·공연 취소·사본 반영·최종 방어선, 관리자 예매 목록 `performanceId` 필터, OpenAPI 스펙과 BFF 경계 스키마 재생성, `web/src/app/admin`의 예매 상세·공연 목록 화면, 아키텍처·흐름·이벤트·API·ERD·런북 문서.
- 범위 밖: 정산 전체 조회, 운영 도구(이벤트 재처리·배치 실행), 대시보드(로드맵 같은 항목의 후속 계획), 사용된 쿠폰 복원·재발급, 회차 단위 취소, 회원 대상 취소 알림 발송, payment·settlement 앱 코드 변경, Kafka 계약 클래스(`contract`) 변경.

## task

### T1. 관리자 예매 강제 취소 (booking·audit)

- [x] 완료
- Blocked by: 없음
- 변경 범위와 제약: `CancelReason`에 `ADMIN_CANCELLED`, `PERFORMANCE_CANCELLED`를 추가한다(`cancel_reason`은 `varchar(30)`이고 CHECK 제약이 없어 마이그레이션은 필요 없다). 도메인 `Booking.cancelByUser`의 상태별 전이를 사유를 받는 공용 취소 메서드로 일반화하고, 사용자 취소는 소유자 검사 후 이를 호출한다. `CancelBookingService`의 트랜잭션 처리(환불 요청·`sold_seat` 삭제·쿠폰 복원·`BookingCancelled` 발행·커밋 후 Redis)를 사유를 받는 내부 경로로 추출해 사용자 취소·강제 취소·T4 Saga가 공유하게 한다. 사용자 취소의 동작과 응답은 바꾸지 않는다. `POST /api/v1/admin/bookings/{id}/cancellation`(본문 `reason` 필수, 공백 제거 후 1~200자)을 `AdminBookingController`에 추가하고 ADMIN만 허용한다. EXPIRED·CANCELLED는 409로 거부한다. booking 패키지 루트에 core 내부 이벤트 `BookingForceCancelled(eventId, actorId, bookingId, reason, cancelledAt)`를 두고 같은 트랜잭션에서 발행한다. audit에 `BookingAuditListener`를 추가해 `AdminActionRecorder`로 멱등 기록한다. 이 이벤트는 Kafka로 외부화하지 않는다(`BookingEventExternalizationConfig` 변경 없음). `BookingCancelled` 클래스는 바꾸지 않는다.
- 테스트 경계와 선례: HTTP 공개 경계 통합 테스트는 `AdminBookingIntegrationTests`에, 도메인 전이는 `BookingTests`에, 감사는 `AuditLogIntegrationTests`에 추가한다. PENDING 강제 취소는 선점 해제와 쿠폰 복원 요청을, CONFIRMED 강제 취소는 `RefundRequested`·`BookingCancelled(reason=ADMIN_CANCELLED, refundRequired=true)` 발행과 `sold_seat` 삭제를 확인한다. EXPIRED·CANCELLED는 409, 사유 누락·201자는 400, USER·ORGANIZER는 403, 없는 ID는 404를 확인한다. 감사 로그 1건과 재전달 멱등도 확인한다. 기존 사용자 취소 테스트(`BookingApiIntegrationTests`, `BookingPaymentFlowTests`)가 그대로 통과해야 한다. `ModularityTests`를 같은 실행에 포함한다.
- AC:

  ```bash
  ./scripts/agent/test/run-tests.sh :core:test --tests '*AdminBooking*' --tests '*BookingTests' --tests '*BookingApiIntegration*' --tests '*BookingPaymentFlow*' --tests '*AuditLog*' --tests '*ModularityTests'
  ```

### T2. 공연 취소 상태와 API (catalog·audit)

- [x] 완료
- Blocked by: 없음
- 변경 범위와 제약: Flyway `V22`로 `catalog.performance_status_check`에 `CANCELLED`를 추가하고 `PerformanceStatus.CANCELLED`를 추가한다. `PUT /api/v1/admin/performances/{id}/cancellation`(본문 `reason` 필수, 최대 200자)을 `AdminPerformanceController`에 추가한다. `ACTIVE`·`SUSPENDED`에서만 전이하고 이미 `CANCELLED`면 409를 반환한다. 취소된 공연의 판매 중지·재개도 409로 거부한다. 상태 번호(`suspensionVersion`)는 다른 상태 변경과 같은 순번을 이어 쓴다. 공개 목록 제외·상세 404·공개 캐시 무효화와 주최자의 수정·회차 추가 거부는 `SUSPENDED`와 같게 적용한다. 주최자 전용 상세와 정산 조회는 유지한다. 관리자 공연 목록의 상태 필터와 응답에 `CANCELLED`를 노출한다. catalog 패키지 루트에 core 내부 이벤트 `PerformanceCancelled(eventId, actorId, performanceId, version, reason, cancelledAt)`를 두고 발행한다. `PerformanceAuditListener`가 이를 `PERFORMANCE_CANCELLED` 동작으로 멱등 기록한다. 이 task에서는 booking 리스너를 만들지 않는다.
- 테스트 경계와 선례: `AdminPerformanceIntegrationTests`와 `CatalogQueryApiTests`·`CatalogRegisterApiTests`의 패턴을 따른다. 다음을 확인한다.
  - `ACTIVE`·`SUSPENDED`에서 취소 성공
  - 재취소·재개·판매 중지 요청 409
  - 취소 직후 공개 목록 제외·상세 404(캐시 무효화)
  - 주최자의 수정·회차 추가 거부, 주최자 상세 조회 유지
  - 권한 403, 사유 검증 400
  - 감사 로그 1건과 멱등
- AC:

  ```bash
  ./scripts/agent/test/run-tests.sh :core:test --tests '*AdminPerformance*' --tests '*Catalog*' --tests '*AuditLog*' --tests '*ModularityTests'
  ```

### T3. booking 사본의 공연 취소 반영과 최종 방어선

- [x] 완료
- Blocked by: T1, T2
- 변경 범위와 제약: Flyway `V23`로 `booking.performance_sale`과 `booking.schedule`에 `cancelled boolean NOT NULL DEFAULT false`를 추가한다. `ScheduleSeatPersistenceAdapter`의 상태 반영은 기존 `status_version` 비교 규칙으로 판매 가능 여부와 취소 여부를 함께 갱신한다. 취소를 반영할 때는 `sale_enabled=false`로 둔다. 회차 등록이 취소보다 늦게 와도 `lockSaleStatus` 경로에서 취소 여부를 사본에 복사한다. `ScheduleRegisteredListener`(또는 같은 패키지의 새 리스너)가 `PerformanceCancelled`를 받아 반영한다. 이 task의 리스너는 사본 반영까지만 하고 예매 정리는 T4가 맡는다. 최종 방어선은 두 곳이다. `RequestPaymentService`는 취소된 공연 회차의 결제 요청을 거부한다(판매 중지는 허용). `PaymentResultService`의 확정 경로는 `sold_seat` 삽입 전에 취소 여부를 확인한다. 취소됐으면 예매를 `CANCELLED(PERFORMANCE_CANCELLED)`로 바꾸고 `RefundRequested`·쿠폰 복원·`BookingCancelled(refundRequired=true)`를 발행한 뒤 커밋 후 선점을 해제한다. 이 처리는 기존 `cancelWithRefund` 경로를 쓴다. 판매 중지된 공연의 PENDING 결제는 지금처럼 확정한다(ADR 0052 결정 1).
- 테스트 경계와 선례: `ScheduleSuspensionIntegrationTests`, `ScheduleSeatPersistenceAdapterTests`, `BookingPaymentFlowTests`의 패턴을 따른다. 다음을 확인한다.
  - 취소 반영 후 신규 선점·예매 생성 거부
  - 취소 전에 만든 PENDING 예매의 결제 요청 거부
  - 취소 반영 후 결제 성공이 도착하면 확정되지 않고 `PERFORMANCE_CANCELLED`로 취소·환불 요청됨
  - 판매 중지 공연의 PENDING 결제는 여전히 확정됨
  - 이벤트 재전달·순서 역전(옛 판매 중지·재개 이벤트가 취소보다 늦게 도착)에서 취소 유지
  - 회차 등록이 취소보다 늦게 와도 취소 반영
- AC:

  ```bash
  ./scripts/agent/test/run-tests.sh :core:test --tests '*ScheduleSuspension*' --tests '*ScheduleCancellation*' --tests '*ScheduleSeatPersistenceAdapter*' --tests '*BookingPaymentFlow*' --tests '*CreateBooking*' --tests '*ModularityTests'
  ```

### T4. 공연 취소 Saga의 예매 일괄 취소와 진행 확인 필터

- [x] 완료
- Blocked by: T3
- 변경 범위와 제약: T3의 `PerformanceCancelled` 처리에 예매 일괄 취소를 이어 붙인다. 사본 반영 트랜잭션이 커밋된 뒤, 해당 공연 회차의 PENDING·CONFIRMED 예매 ID를 `booking.schedule.performance_id` 기준(같은 스키마 안의 조인)으로 청크 단위로 조회한다. 청크마다 별도 트랜잭션에서 T1의 공용 취소 경로(사유 `PERFORMANCE_CANCELLED`)로 취소한다. 커밋 후 Redis 반영은 예매마다 기존 방식을 따르고, 실패해도 로그만 남긴다. 청크 크기는 설정값(기본 100)으로 둔다. 리스너 트랜잭션 안에서 커넥션을 하나 더 잡지 않게 한다(`PaymentResultService` 주석의 풀 고갈 사유). 이 처리는 트랜잭션 없이 실행하는 리스너가 청크 트랜잭션을 차례로 여는 구조로 만들고, `@ApplicationModuleListener`의 기본 트랜잭션 전파와 충돌하지 않는지 프로젝트 Spring Modulith 버전 문서로 확인한다. 중간 실패는 예외를 전파해 이벤트 발행 레지스트리의 재발행에 맡긴다. 재실행 때 이미 취소된 예매는 조회 조건에서 빠진다. 예매 동시 변경으로 인한 낙관적 락 충돌은 해당 예매를 다시 읽어 처리하거나 예외를 전파한다. 관리자 예매 목록(`AdminBookingFilter`, `AdminBookingQueryJooqAdapter`)에 `performanceId` 필터를 추가한다(같은 스키마의 `booking.schedule` 조인). 관리자 예매 상세 응답에 선택적인 `cancelReason` 취소 유형을 추가한다. 관리자가 입력한 사유 원문은 감사 로그에만 보관한다.
- 테스트 경계와 선례: 통합 테스트 `PerformanceCancellationSagaIntegrationTests`(신규)는 `ScheduleSuspensionIntegrationTests`와 `BookingCouponSagaTests`를 선례로 삼는다. 다음을 확인한다.
  - 청크 크기보다 많은 예매(PENDING·CONFIRMED·EXPIRED·CANCELLED 혼합)의 처리 결과: PENDING·CONFIRMED만 `PERFORMANCE_CANCELLED`, CONFIRMED만 `RefundRequested`·`sold_seat` 삭제, PENDING은 쿠폰 복원 요청, 다른 공연의 예매는 그대로
  - 중간 청크에서 실패시킨 뒤 재실행했을 때 중복 환불·중복 `BookingCancelled`가 없음
  - 관리자 예매 목록 `performanceId` 필터 결과와 관리자 상세 응답의 취소 유형
- AC:

  ```bash
  ./scripts/agent/test/run-tests.sh :core:test --tests '*PerformanceCancellationSaga*' --tests '*AdminBooking*' --tests '*BookingSettlementExternalization*' --tests '*ModularityTests'
  ```

### T5. OpenAPI 스펙과 BFF 경계 스키마 재생성

- [x] 완료
- Blocked by: T1, T2, T4
- 변경 범위와 제약: [ADR 0047](../../adr/0047-openapi-codegen-for-bff-boundary.md) 절차대로 core 스펙(`docs/api/openapi/`)과 `web`의 Zod 스키마를 재생성한다. 생성 파일은 손으로 고치지 않는다. `web/src/server/api/admin.ts`에 강제 취소·공연 취소 호출 함수와 예매 목록 `performanceId` 파라미터를 추가한다.
- 테스트 경계와 선례: `web/src/server/api/admin.test.ts`. 요청 경로·본문·응답 파싱과 409·400 오류 매핑을 단위 테스트로 확인한다.
- AC:

  ```bash
  ./scripts/agent/test/run-tests.sh :core:test --tests '*OpenApiContractTests'
  pnpm --dir web run api:check
  pnpm --dir web exec vitest run src/server/api/admin.test.ts
  ```

### T6. web 관리자 화면

- [x] 완료
- Blocked by: T5
- 변경 범위와 제약: [프런트엔드 지침](../../rules/frontend.md)과 U 트랙 토큰·프리미티브 규칙을 따른다. `/admin/bookings/[id]`의 PENDING·CONFIRMED 예매에 "강제 취소"(사유 입력, 확인 단계, 확정 예매는 환불된다는 안내)를 추가한다. `/admin/performances`의 `ACTIVE`·`SUSPENDED` 공연에 "공연 취소"(사유 입력, 되돌릴 수 없고 예매가 모두 취소·환불된다는 경고, 확인 단계)를 추가한다. `CANCELLED` 상태 표시와 필터를 추가하고, 취소된 공연에서는 중지·재개 버튼을 숨긴다. 취소된 공연 행에서 `/admin/bookings?performanceId=…` 목록으로 연결한다. 예매 상태·취소 사유 표시(`booking-status.ts`)에 새 사유를 추가한다. 서버 액션은 `features/admin/`에 두고 매번 `requireAdmin`을 다시 검사한다. JWT는 브라우저에 노출하지 않는다(규칙 5).
- 테스트 경계와 선례: `features/admin/suspension-actions.test.ts`와 `actions.test.ts`. 서버 액션의 권한 재검사·사유 검증·409 오류 매핑을 단위 테스트로 확인한다. 화면은 `ui-builder` 지시서 방식의 headless 캡처로 확인한다.
- AC:

  ```bash
  pnpm --dir web exec vitest run src/features/admin
  ./scripts/agent/test/verify-changed.sh
  ```

### T7. 문서 갱신

- [ ] 완료
- Blocked by: T1, T2, T3, T4
- 변경 범위와 제약: 아래 문서를 구현된 상태로 맞춘다.
  - [architecture.md](../../architecture.md): audit 이벤트 목록과 모듈 설명
  - [flows.md](../../architecture/flows.md): 예매 상태 전이도, 강제 취소·공연 취소 Saga·최종 방어선
  - [events.md](../../architecture/events.md): `BookingForceCancelled`·`PerformanceCancelled`, `BookingCancelled.reason` 값
  - [api-conventions.md](../../architecture/api-conventions.md): 관리자 API 표
  - [erd.md](../../architecture/erd.md), [data-ownership.md](../../architecture/data-ownership.md): 새 컬럼
  - [runbook](../../runbook.md): 공연 취소 후 남은 예매 확인(`performanceId` 필터, 미완료 `event_publication` 재처리)
  - [로드맵](../../roadmap.md): 이 계획을 별도 구현 항목에 체크하고, 우선 처리 순서 1번과 성능 후속 표의 "관리자 기능 후속"을 남은 하위 항목(정산 전체 조회·운영 도구·대시보드)으로 줄인다

  모듈 의존이 바뀌었으면 `./gradlew :core:modulithDocs`로 `docs/modulith/`를 갱신한다. 구현 중 ADR 0054와 달라진 점은 사용자와 합의한 뒤 변경 이력이나 새 ADR로 남긴다.
- 테스트 경계와 선례: 문서 링크·표기 검사.
- AC:

  ```bash
  ./scripts/agent/docs/check-docs.sh
  ```

## 최종 검증

- 시작 SHA부터 HEAD까지 `./scripts/agent/test/verify-changed.sh <시작-SHA>..HEAD`를 실행한 뒤 필터 없는 `./scripts/agent/test/run-tests.sh`를 순서대로 실행한다. jOOQ 코드 생성과 Testcontainers에 Docker가 필요하다.
- `review-diff`로 시작 SHA부터 HEAD까지 검토한다. 이 계획과 ADR 0054를 명세로 쓴다.
- 전체 E2E(결제·환불 경로 포함)는 PR의 CI에서 확인한다. 로컬 E2E는 요청이 있을 때만 실행한다.
