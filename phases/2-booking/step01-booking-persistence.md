# Step 1: booking-persistence

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- step 0에서 만든 `core/src/main/kotlin/io/github/ticketing/booking/domain/`
- `core/src/main/kotlin/io/github/ticketing/catalog/ScheduleRegistered.kt` (수신할 이벤트)
- `core/src/main/resources/db/migration/V4__create_catalog_tables.sql` (마이그레이션 형식)
- `docs/rules/backend.md` § 헥사고날, § 영속성, § 코어 모듈 구조(리스너 멱등)

## 작업

### 1. Flyway `V5__create_booking_tables.sql` (모두 `booking` 스키마)

| 테이블 | 컬럼 | 제약 |
|---|---|---|
| `booking.schedule` | `schedule_id bigint PK`, `performance_id`, `performance_title`, `organizer_id`, `starts_at`, `received_at` | catalog 사본이므로 identity가 아니다 |
| `booking.schedule_seat` | `schedule_id`, `seat_id`, `grade`, `label`, `price numeric(12,0)` | `PK (schedule_id, seat_id)` |
| `booking.booking` | `id` identity, `member_id`, `schedule_id`, `status varchar(20)`, `total_amount numeric(12,0)`, `hold_token varchar(36)`, `idempotency_key varchar(100)`, `expires_at`, `payment_requested_at` null, `confirmed_at` null, `cancelled_at` null, `cancel_reason` null, `created_at`, `version bigint` | `UNIQUE (member_id, idempotency_key)`, `(member_id, id desc)` 인덱스 |
| `booking.booking_seat` | `booking_id` FK, `seat_id`, `price` | `PK (booking_id, seat_id)` |
| `booking.sold_seat` | `schedule_id`, `seat_id`, `booking_id`, `sold_at` | `PRIMARY KEY (schedule_id, seat_id)`. **좌석 중복 판매의 최종 방어선** |

- `catalog` 스키마로 FK를 만들지 않는다.
- `booking.version`은 JPA `@Version` 낙관적 락에 쓴다. 결제 결과 리스너와 사용자 취소가 동시에 같은 예매를 바꿀 수 있다.

### 2. 출력 포트 (`booking.application.port.required`)

```kotlin
interface BookingCommandPort {
    fun save(booking: Booking): Booking
    fun findById(id: BookingId): Booking?
    fun findByMemberAndIdempotencyKey(memberId: MemberId, key: String): Booking?
    /** INSERT ... ON CONFLICT DO NOTHING. 삽입된 행 수를 반환한다. */
    fun insertSoldSeats(bookingId: BookingId, scheduleId: ScheduleId, seatIds: List<SeatId>, now: Instant): Int
    fun deleteSoldSeats(bookingId: BookingId)
}
interface ScheduleSeatCommandPort {
    /** 이미 있으면 아무것도 하지 않는다(멱등). 새로 저장했으면 true */
    fun saveIfAbsent(schedule: ScheduleCopy, seats: List<ScheduleSeatCopy>): Boolean
    fun findSeats(scheduleId: ScheduleId, seatIds: Collection<SeatId>): List<ScheduleSeatCopy>
}
```

- `ScheduleCopy`와 `ScheduleSeatCopy`는 `booking.domain`에 두는 순수 값 타입이다.
- `insertSoldSeats`가 `ON CONFLICT DO NOTHING`을 쓰는 이유: PostgreSQL은 제약 위반이 나면 트랜잭션을 abort 상태로 만들어 이후 쓰기를 막는다. 삽입 수가 좌석 수보다 적으면 호출자(step 4)가 예외로 롤백하고 별도 트랜잭션에서 실패를 처리한다. 이 방식도 결국 유니크 제약에 의존하므로 방어선 원칙은 그대로 지켜진다. 네이티브 쿼리는 JPA 어댑터 안에 둔다.

### 3. JPA 어댑터 (`booking.adapter.required.jpa`)

- JPA 엔티티는 도메인 모델과 분리하고 매퍼로 변환한다. `BookingJpaEntity`와 `booking_seat`는 같은 애그리거트로 `@OneToMany(LAZY)`다.
- 도메인 `Booking`의 낙관적 락 버전을 매퍼가 보존해야 한다. 그래야 갱신할 때 `@Version`이 동작한다.

### 4. 유스케이스와 리스너

- `booking.application.port.provided.RegisterScheduleSeatsUseCase` + `application.service` 구현(`@Transactional`)
- `booking.adapter.provided.event.ScheduleRegisteredListener`: `@ApplicationModuleListener`로 `ScheduleRegistered`를 받아 유스케이스를 호출한다. 같은 이벤트가 다시 와도 결과가 같아야 한다.
- Redis 스냅샷 초기화는 step 2에서 이 유스케이스에 붙인다. 지금은 DB 사본만 만든다.

### 5. 테스트

- `core/src/test/kotlin/io/github/ticketing/booking/adapter/required/jpa/`: 저장·조회 왕복, 멱등 키 유니크, `insertSoldSeats`의 충돌 시 삽입 수(두 예매가 같은 좌석을 넣으면 두 번째는 0), `deleteSoldSeats` 후 재삽입 가능
- `core/src/test/kotlin/io/github/ticketing/booking/adapter/provided/event/`: `ScheduleRegistered`를 발행하면 사본이 생기고, 두 번 발행해도 행 수가 같다. 비동기 리스너는 Awaitility로 기다린다(`Thread.sleep` 금지). Awaitility는 `spring-modulith-starter-test`에 포함되는지 먼저 확인하고, 없으면 `core/build.gradle.kts`에 `testImplementation("org.awaitility:awaitility-kotlin")`를 추가한다.

### 6. 로드맵

`docs/roadmap.md` M1-3의 "`(회차, 좌석)` 유니크 제약"을 `[x]`로 바꾼다.

## Acceptance Criteria

```bash
test -f core/src/main/resources/db/migration/V5__create_booking_tables.sql
rg -q 'sold_seat' core/src/main/resources/db/migration/V5__create_booking_tables.sql
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.booking.adapter.*" --tests "io.github.ticketing.ModularityTests" --tests "io.github.ticketing.HexagonalArchitectureTests"
rg -q '^- \[x\] `\(회차, 좌석\)` 유니크 제약' docs/roadmap.md
```

## 검증 절차

1. 위 AC를 실행하고, 실패하면 원인을 수정한다.
2. `phases/2-booking/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "생성/수정한 파일 경로와 핵심 결론을 2줄·200자 이내로"`
   - 해결할 수 없는 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- catalog 스키마를 조회하거나 catalog 내부 클래스(`catalog.domain`, `catalog.repository` 등)를 import하지 마라. 이벤트 타입 `ScheduleRegistered`와 `ScheduleSeat`만 쓴다. 이유: 설계 규칙 1, 2
- 유니크 위반을 잡으려고 `DataIntegrityViolationException`을 catch한 뒤 같은 트랜잭션에서 쓰기를 이어가지 마라. 이유: PostgreSQL 트랜잭션이 abort 상태가 된다.
- JPA 엔티티를 도메인 계층이나 `port`에 노출하지 마라. 이유: 도메인과 엔티티 분리 규칙
- 컨트롤러와 Redis 코드를 만들지 마라. 이유: step 2, 3의 범위다.
- 검색은 `rg`/`fd`를 우선 쓴다. 이유: `.gitignore`를 존중해 `build/` 결과가 섞이지 않고 출력이 짧다. 사용 가능한 도구는 Bash, Read, Write, Edit뿐이다.
- 기존 테스트를 깨뜨리지 마라.
- git commit 하지 마라. 이유: execute.py가 AC 통과를 확인한 뒤 step 단위로 커밋한다.
