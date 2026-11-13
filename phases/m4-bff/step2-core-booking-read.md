# Step 2: core-booking-read

- model: 기본 / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `core/src/main/kotlin/io/github/ticketing/booking/adapter/provided/web/BookingController.kt`
- `core/src/main/kotlin/io/github/ticketing/booking/application/port/provided/BookingQueryUseCase.kt`, `BookingResults.kt`
- `core/src/main/kotlin/io/github/ticketing/booking/application/port/required/BookingQueryPort.kt`, `application/service/BookingQueryService.kt`
- `core/src/main/kotlin/io/github/ticketing/booking/adapter/required/jooq/BookingQueryJooqAdapter.kt`
- `core/src/main/kotlin/io/github/ticketing/booking/domain/Booking.kt` (PENDING 만료 판정, 결제 요청 유예 규칙)
- `core/src/main/resources/db/migration/V5__create_booking_tables.sql` (`booking.schedule`, `schedule_seat`, `booking`, `booking_seat`, `sold_seat`)
- `core/src/main/kotlin/io/github/ticketing/member/security/SecurityConfig.kt`
- `core/src/test/kotlin/io/github/ticketing/booking/adapter/provided/web/BookingApiIntegrationTests.kt`
- `docs/rules/backend.md § 헥사고날`, `§ 영속성`, `§ API`, `§ 테스트`

## 작업

BFF 조합 화면용 조회 API를 `booking` 모듈에 추가한다. 모든 조회는 `booking` 스키마 안에서 jOOQ로 한다. `booking.schedule`은 catalog 회차의 사본이며 `performance_id`, `performance_title`, `organizer_id`를 가진다.

### 1. 회차별 잔여 좌석 `GET /api/v1/schedules/availability?scheduleIds=1,2`

```kotlin
data class ScheduleAvailability(val scheduleId: Long, val totalSeats: Int, val availableSeats: Int)
```

- 공개 경로(GET만). `SecurityConfig`에 추가한다.
- `totalSeats` = `schedule_seat` 수. `availableSeats` = `totalSeats` − `sold_seat` 수 − 아직 유효한 PENDING 예매의 좌석 수(`booking_seat` 기준). 0 미만이면 0.
- "유효한 PENDING"은 `Booking.kt`의 만료 판정과 같은 규칙으로 SQL 조건을 만든다(`expires_at`와 결제 요청 시 유예). 기준 시각은 주입된 `Clock`.
- `scheduleIds`는 1~50개, 아니면 400 `INVALID_REQUEST`. `booking.schedule`에 없는 회차는 결과에서 뺀다.
- 회차 여러 개를 한 번의 쿼리(또는 회차 수와 무관한 고정 횟수)로 계산한다. 회차마다 쿼리를 반복하지 마라.

### 2. 내 예매 목록 확장 `GET /api/v1/bookings/me`

- 항목에 `performanceId: Long`을 추가한다(`booking.schedule` 조인, 같은 스키마).
- 선택 쿼리 `performanceId`로 필터한다. 기존 cursor·size 동작은 그대로.

### 3. 주최자 판매 현황 `GET /api/v1/organizer/sales`

```kotlin
data class ScheduleSales(
    val performanceId: Long, val performanceTitle: String, val scheduleId: Long, val startsAt: Instant,
    val totalSeats: Int, val soldSeats: Int, val confirmedAmount: BigDecimal,
)
```

- ORGANIZER 역할만. 인증 회원 ID = `booking.schedule.organizer_id`인 회차만 반환한다. 다른 주최자의 회차는 절대 포함하지 않는다.
- `soldSeats` = `sold_seat` 수, `confirmedAmount` = `CONFIRMED` 예매 `total_amount` 합(없으면 0).
- 정렬: `performanceId` 오름차순, `startsAt` 오름차순. 페이징 없음.

### 4. 테스트: `core/src/test/kotlin/io/github/ticketing/booking/adapter/provided/web/BookingReadApiIntegrationTests.kt`

`IntegrationTestSupport`를 상속하고 `Clock` 고정 규칙(`docs/rules/backend.md § 테스트`)을 따른다. 다룰 경우:

- 잔여 좌석: 판매 좌석과 유효한 PENDING 좌석은 빠지고, 만료된 PENDING 좌석은 다시 포함된다. 없는 회차는 결과에서 빠진다. 51개면 400. 토큰 없이 호출 가능.
- 내 예매: 항목에 `performanceId`가 있고, `performanceId` 필터가 다른 공연 예매를 제외한다.
- 판매 현황: 본인 회차만 나오고 다른 주최자 회차는 없다. USER면 403, 토큰 없으면 401.

## Acceptance Criteria

```bash
rg -q 'schedules/availability' core/src/main/kotlin/io/github/ticketing/booking
rg -q 'organizer/sales' core/src/main/kotlin/io/github/ticketing/booking
./scripts/agent/test/run-tests.sh :core:test --tests "*BookingReadApiIntegrationTests" --tests "*BookingApiIntegrationTests" --tests "*ModularityTests"
./scripts/agent/test/check-summary.sh :core:ktlintCheck :core:detekt
```

## 검증 절차

1. 새 테스트부터 통과시킨 뒤 위 AC 전체를 실행한다.
2. 마지막에 `./scripts/agent/test/run-tests.sh :core:test`로 코어 전체 회귀를 확인한다.

## 금지사항

- `catalog`, `payment`, `settlement` 스키마를 조인하거나 해당 모듈 클래스를 참조하지 마라. 이유: 스키마 간 조인·모듈 직접 참조 금지(AGENTS.md 4절). 필요한 값은 `booking.schedule` 사본에 있다.
- 잔여 좌석을 Redis 스냅샷에서 읽지 마라. 이유: 합의된 정의는 DB 기준이며, Redis는 표시용 실시간 경로(SSE)가 담당한다.
- 판매 현황에서 주최자 ID를 쿼리 파라미터로 받지 마라. 이유: 다른 주최자 데이터 조회가 가능해진다. JWT의 회원 ID만 쓴다.
- 기존 `/bookings/me` 응답 필드를 지우거나 이름을 바꾸지 마라. 이유: 기존 테스트와 e2e 모듈이 쓴다.
