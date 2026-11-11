# Step 8: seat-snapshot-reconcile-job

- model: 기본 / reasoning_effort: high

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `core/src/main/kotlin/io/github/ticketing/booking/adapter/required/redis/RedisSeatHoldAdapter.kt` (스크립트 로딩 `SeatHoldScriptConfig`, 키 함수 `holdKey`·`snapshotKey`·`streamKey`)
- `core/src/main/resources/redis/booking/*.lua` (특히 `mark-sold.lua`, `mark-available.lua`의 스냅샷·Stream 갱신 형식)
- `core/src/main/kotlin/io/github/ticketing/booking/application/port/required/SeatHoldPort.kt`, `BookingQueryPort.kt`
- `core/src/test/kotlin/io/github/ticketing/booking/adapter/required/redis/RedisSeatHoldAdapterTests.kt`
- `core/src/main/kotlin/io/github/ticketing/KeysetItemReader.kt`, `core/src/test/kotlin/io/github/ticketing/BatchIntegrationTestSupport.kt` (step 1)
- `docs/architecture.md § 5. 데이터 > Redis 용도`(좌석 선점 키 형식), `§ 9. Batch`

## 작업

DB 커밋 뒤 Redis 반영이 실패해 어긋난 좌석 스냅샷(`booking:seats:{scheduleId}`)을 DB 기준으로 고치는 Job을 booking 모듈에 만든다.

### 키 형식 (기존, 바꾸지 않는다)

| 키 | 자료구조 | 내용 |
|---|---|---|
| `booking:hold:{<scheduleId>}:<seatId>` | String, PX TTL | 값은 선점 소유자(`HoldToken`) |
| `booking:seats:{<scheduleId>}` | Hash | field `seatId` → `AVAILABLE` / `HELD:<만료 epoch ms>` / `SOLD` |
| `booking:seat-events:{<scheduleId>}` | Stream, `MAXLEN ~ 10000` | `seatIds`(쉼표 구분), `status`, `holdExpiresAt`(HELD일 때), `at`(epoch ms) |

### 좌석별 기대 상태

1. `booking.sold_seat`에 있으면 `SOLD`
2. 아니고 선점 키가 있으면 `HELD:<now + 남은 PTTL>`
3. 아니면 `AVAILABLE`

현재 값과 기대 값이 다른 좌석만 `HSET`하고, 바뀐 좌석을 상태별로 묶어 Stream에 기존 형식으로 기록한다(HELD는 만료 시각이 같은 좌석끼리 묶는다). 스냅샷 키가 아예 없으면 모든 좌석을 기록한다. 바뀐 것이 없으면 Stream에 쓰지 않는다.

### Redis

- `core/src/main/resources/redis/booking/reconcile-snapshot.lua`: KEYS = 대상 좌석 선점 키들 + 스냅샷 키 + Stream 키, ARGV = now(epoch ms), 좌석 ID 목록, SOLD 좌석 ID 목록. 위 규칙을 한 번의 스크립트 실행으로 처리하고 바뀐 좌석 수를 반환한다. 모든 키는 `{scheduleId}` 해시 태그로 같은 슬롯이다.
- `SeatHoldPort`에 `fun reconcileSnapshot(scheduleId: ScheduleId, seatIds: List<SeatId>, soldSeatIds: Set<SeatId>, now: Instant): Int` 추가, `RedisSeatHoldAdapter`에 구현, 스크립트 빈을 `SeatHoldScriptConfig`에 추가.

### 유스케이스와 조회

```
application.port.provided.ReconcileSeatSnapshotUseCase
    fun reconcile(scheduleId: Long): Int                 // 바뀐 좌석 수
application.port.required.BookingQueryPort               // 메서드 추가 (jOOQ)
    fun findUpcomingScheduleIds(now: Instant, afterId: Long?, limit: Int): List<Long>   // starts_at > now
    fun findSeatIds(scheduleId: Long): List<Long>                                        // booking.schedule_seat
    fun findSoldSeatIds(scheduleId: Long): Set<Long>                                     // booking.sold_seat
application.service.ReconcileSeatSnapshotService
```

- `reconcile`: 좌석·판매 좌석 조회 → `reconcileSnapshot` → **판매 좌석을 한 번 더 조회**해 그 사이 새로 판매된 좌석이 있으면 그 좌석만 다시 `SOLD`로 맞춘다(같은 스크립트를 해당 좌석으로 다시 호출해도 된다). 이유: 첫 조회 뒤 커밋된 확정의 `SOLD`를 스크립트가 `AVAILABLE`로 덮는 경쟁을 줄인다.
- 남는 경쟁(첫 조회 뒤 커밋된 취소)은 다음 실행에서 고쳐진다. 스냅샷은 표시용이고 판매 여부의 최종 판단은 선점 키와 DB 유니크 제약이라는 점을 서비스 KDoc 한 줄로 남긴다.
- Redis 작업은 DB 트랜잭션 밖에서 한다. 조회는 `readOnly` 트랜잭션.

### Job: `booking.adapter.provided.batch.ReconcileSeatSnapshotJobConfig` (`@Profile("batch")`)

- Job 이름 `reconcileSeatSnapshotJob`. 기준 시각은 Step 시작 시 `Clock`으로 정해 `ExecutionContext`에 둔다.
- reader: `KeysetItemReader`(키 `scheduleId`) + `findUpcomingScheduleIds`. writer: 회차마다 `reconcile`. 회차 하나의 Redis 오류가 다른 회차 보정을 막지 않게 한다(건너뛰고 로그).
- 회차별 변경 수와 전체 합계를 로그로 남긴다.

### 테스트

- `RedisSeatHoldAdapterTests`에 `reconcileSnapshot` 사례 추가: DB 판매인데 스냅샷 `AVAILABLE` → `SOLD` / 스냅샷 `SOLD`인데 판매 아님·선점 없음 → `AVAILABLE` / 선점 키 있음 → `HELD:<만료>` / 만료된 `HELD`, 선점 키 없음 → `AVAILABLE` / 스냅샷 키 없음 → 전체 생성 / 변경 없음 → Stream 항목 없음 / 변경 시 Stream 항목 형식.
- `core/src/test/kotlin/io/github/ticketing/booking/adapter/provided/batch/ReconcileSeatSnapshotJobTests.kt` (`BatchIntegrationTestSupport` 상속): 이미 시작한 회차는 건드리지 않음, 어긋난 스냅샷이 고쳐짐, **두 번째 실행은 변경 0건**.

## Acceptance Criteria

```bash
test -f core/src/main/resources/redis/booking/reconcile-snapshot.lua
rg -q 'reconcileSeatSnapshotJob' core/src/main/kotlin/io/github/ticketing/booking/adapter/provided/batch
rg -q 'fun reconcileSnapshot' core/src/main/kotlin/io/github/ticketing/booking/application/port/required/SeatHoldPort.kt
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.booking*" --tests "*HexagonalArchitectureTests"
./scripts/agent/test/check-summary.sh :core:ktlintCheck :core:detekt
```

## 검증 절차

1. `run-tests.sh :core:test --tests "*RedisSeatHoldAdapterTests"` → `*ReconcileSeatSnapshotJobTests` 순서로 통과시킨 뒤 AC 전체를 실행한다.

## 금지사항

- 선점 키를 만들거나 지우거나 TTL을 바꾸지 마라. 이유: 선점 가능 여부의 기준은 선점 키다. 보정 Job은 스냅샷과 Stream만 쓴다.
- 좌석 여러 개를 여러 Redis 명령으로 나눠 갱신하지 마라. 이유: 동시에 실행되는 선점 Lua와 섞이면 스냅샷이 다시 어긋난다. 한 회차는 스크립트 한 번으로 처리한다.
- 기존 키 형식·Stream 필드 이름을 바꾸지 마라. 이유: realtime 앱(M3)이 이 형식을 읽는다.
- 모든 회차를 한 번에 메모리에 올리지 마라. 이유: chunk 지향 처리 규칙. 회차 단위 keyset으로 읽는다.
- `KEYS`·`SCAN`으로 Redis 키를 훑어 대상을 찾지 마라. 이유: 기준은 DB이며 대상 회차·좌석은 DB에서 정한다.
- 기존 테스트를 깨뜨리지 마라.
