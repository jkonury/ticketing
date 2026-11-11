# Step 7: booking-expire-job

- model: 기본 / reasoning_effort: high

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `core/src/main/kotlin/io/github/ticketing/booking/domain/Booking.kt` (`isExpired`, `expire`, `paymentRequestedAt`)
- `core/src/main/kotlin/io/github/ticketing/booking/application/service/RequestPaymentService.kt` (기존 만료 경로: 저장 → `BookingExpired` 발행 → 커밋 후 `seatHold.release`)
- `core/src/main/kotlin/io/github/ticketing/booking/application/port/required/SeatHoldPort.kt`, `BookingCommandPort.kt`
- `core/src/main/kotlin/io/github/ticketing/booking/adapter/required/jooq/BookingQueryJooqAdapter.kt` (jOOQ 조회 형태)
- `core/src/main/kotlin/io/github/ticketing/KeysetItemReader.kt`, `core/src/test/kotlin/io/github/ticketing/BatchIntegrationTestSupport.kt` (step 1)
- `docs/architecture.md § 6.3 예매 상태 전이`, `§ 9. Batch`
- `docs/rules/backend.md § Batch (core-batch)`, `§ 트랜잭션과 동시성`, `§ 테스트`

## 작업

`PENDING`으로 남은 만료 예매를 `EXPIRED`로 바꾸는 Job을 booking 모듈에 만든다.

### 만료 대상 규칙 (합의된 결정, 도메인에 둔다)

- 결제를 요청하지 않은 `PENDING`(`paymentRequestedAt == null`): `now >= expiresAt`이면 대상.
- 결제를 요청한 `PENDING`: `now >= expiresAt + paymentGrace`이면 대상. 유예는 `ticketing.booking.expiry.payment-grace`(기본 `30m`).
- 그 밖의 상태(`CONFIRMED`, `CANCELLED`, `EXPIRED`)는 대상이 아니다.
- 만료 뒤 결제 성공이 도착하면 기존 `PaymentResultService`가 `EXPIRED` 예매에 대해 환불을 요청한다. 이 경로는 바꾸지 않는다.

```kotlin
// booking.domain.Booking
fun isSweepable(now: Instant, paymentGrace: Duration): Boolean
fun expireBySweep(now: Instant, paymentGrace: Duration)   // 대상이 아니면 InvalidBookingStateException
```

기존 `expire(now)`(결제 미요청 전제)의 동작은 바꾸지 않는다.

### 유스케이스

```
application.port.provided.ExpireBookingUseCase
    fun expireIfDue(bookingId: Long): ExpireResult      // EXPIRED | SKIPPED(사유)
application.port.required.BookingQueryPort              // 메서드 추가
    fun findSweepCandidateIds(cutoff: Instant, paymentCutoff: Instant, afterId: Long?, limit: Int): List<Long>
application.service.ExpireBookingService
```

- `expireIfDue`: 예매를 다시 읽어 도메인 규칙으로 재판정한다(읽은 뒤 결제 결과가 먼저 반영됐을 수 있다). 대상이면 `expireBySweep` → 저장 → `BookingExpired` 발행 → **커밋 후** `seatHold.release(scheduleId, seatIds, holdToken, now)`. 기존 만료 경로와 같은 순서다. 해제 실패는 경고 로그(`bookingId` 포함)만 남긴다.
- 후보 조회 SQL은 `status = 'PENDING' AND ((payment_requested_at IS NULL AND expires_at <= :cutoff) OR (payment_requested_at IS NOT NULL AND expires_at <= :paymentCutoff))`, `id` 오름차순 keyset.

### Job: `booking.adapter.provided.batch.ExpirePendingBookingsJobConfig` (`@Profile("batch")`)

- Job 이름 `expirePendingBookingsJob`. 날짜 파라미터는 없다. 기준 시각은 Step 시작 시 `Clock`으로 한 번 정하고 `ExecutionContext`에 저장한다(재시작해도 같은 기준).
- reader: `KeysetItemReader`(키 `bookingId`) + `findSweepCandidateIds`. writer: 항목마다 `ExpireBookingUseCase.expireIfDue`.
- **한 예매의 실패가 다른 예매의 만료를 되돌리지 않아야 한다.** 낙관적 락 충돌(동시에 결제 결과가 반영됨)이나 상태 변경은 건너뛰고 다음 실행에서 다시 판정되게 한다. 구현 방식(항목별 트랜잭션, fault-tolerant skip 등)은 Spring Batch 6 문서를 확인해 정하되, `REQUIRES_NEW`를 쓰면 그 이유를 KDoc에 남긴다.
- 반복 실행이 기본이므로 같은 파라미터로 재실행하는 방법을 step 5에서 정한 정책과 맞춘다.
- 실행 결과(만료 건수, 건너뛴 건수)를 로그로 남긴다.

### 테스트

- 도메인: `core/src/test/kotlin/io/github/ticketing/booking/domain/`의 기존 Booking 테스트 파일에 `isSweepable`·`expireBySweep` 허용·금지 경로 추가(미요청 만료 전후, 요청 + 유예 전후, 다른 상태).
- `core/src/test/kotlin/io/github/ticketing/booking/adapter/provided/batch/ExpirePendingBookingsJobTests.kt` (`BatchIntegrationTestSupport` 상속, `Clock` 고정)
  - 미요청 만료 예매 → `EXPIRED`, `BookingExpired` 발행, Redis 선점 키 해제
  - 결제 요청 + 유예 안 → `PENDING` 유지, 유예 경과 → `EXPIRED`
  - 아직 만료 전, `CONFIRMED` → 변화 없음
  - **두 번 실행해도 결과가 같고 `BookingExpired`가 다시 발행되지 않는다**

## Acceptance Criteria

```bash
rg -q 'expirePendingBookingsJob' core/src/main/kotlin/io/github/ticketing/booking/adapter/provided/batch
rg -q 'fun isSweepable' core/src/main/kotlin/io/github/ticketing/booking/domain/Booking.kt
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.booking*" --tests "*ModularityTests" --tests "*HexagonalArchitectureTests"
./scripts/agent/test/check-summary.sh :core:ktlintCheck :core:detekt
```

## 검증 절차

1. 도메인 테스트 → `ExpirePendingBookingsJobTests` 순서로 좁혀 실행해 통과시킨 뒤 AC 전체를 실행한다.

## 금지사항

- Redis 선점 해제를 트랜잭션 안에서 하지 마라. 이유: 롤백되면 Redis만 바뀐다. 커밋 후에 한다.
- 소유자 확인 없이 선점 키를 지우거나 스냅샷을 `AVAILABLE`로 덮지 마라. 이유: 만료 직후 다른 사용자가 같은 좌석을 선점했을 수 있다. 기존 `release`(소유자 비교 후 삭제)를 쓴다.
- 결제를 요청한 예매를 `expiresAt`만 보고 만료시키지 마라. 이유: 결제가 진행 중이며, 합의된 규칙은 유예 후 만료다.
- 후보를 한 번에 모두 읽어 메모리에 올리지 마라. 이유: chunk 지향 처리 규칙.
- `PaymentResultService`의 만료 후 결제 처리 경로를 바꾸지 마라. 이유: 자동 환불 경로는 M1에서 검증됐다.
- 현재 시각을 `Instant.now()`로 얻지 마라. 이유: 주입된 `Clock`을 쓴다.
- 기존 테스트를 깨뜨리지 마라.
