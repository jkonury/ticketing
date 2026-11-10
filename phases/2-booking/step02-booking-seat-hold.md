# Step 2: booking-seat-hold

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- step 0~1에서 만든 `booking.domain`, `booking.application`(특히 `RegisterScheduleSeatsUseCase` 구현)
- `docs/rules/backend.md` § 트랜잭션과 동시성
- `docs/design.md` § Redis 용도, § 6.2 좌석 선점 (`rg -n`으로 찾는다. step 0이 수정했으므로 줄 번호는 다를 수 있다)

## 작업

Redis 좌석 선점 어댑터를 만든다. 선점, 해제, 판매, 판매 해제를 **각각 Lua 스크립트 한 번**으로 처리하고, 같은 스크립트 안에서 스냅샷과 Stream을 갱신한다.

### 1. 키 설계

모든 키에 `{scheduleId}` 해시 태그를 넣어 같은 슬롯에 둔다. 이유: Lua가 여러 키를 원자적으로 다루려면 Redis Cluster에서 같은 슬롯이어야 한다.

| 키 | 자료구조 | 내용 |
|---|---|---|
| `booking:hold:{<scheduleId>}:<seatId>` | String, PX TTL | 값은 `HoldToken`(소유자) |
| `booking:seats:{<scheduleId>}` | Hash | field `seatId` → `AVAILABLE` / `HELD:<만료 epoch ms>` / `SOLD` |
| `booking:seat-events:{<scheduleId>}` | Stream, `MAXLEN ~ 10000` | `seatIds`(쉼표 구분), `status`, `holdExpiresAt`(HELD일 때), `at` |

- 스냅샷의 `HELD`에 만료 시각을 넣는 이유: 선점 키 TTL이 끝나도 Redis는 알려 주지 않는다. 그래서 읽는 쪽(M3 realtime)이 만료를 직접 판정한다.
- **선점 가능 여부의 판단 기준은 스냅샷이 아니라 선점 키와 `SOLD`**다.
- realtime(M3)이 이 키와 Stream 형식을 읽으므로, 정한 형식을 `docs/design.md` § Redis 용도에 표로 적는다. 문구에 `HELD:`가 들어가게 한다.

### 2. 출력 포트 (`booking.application.port.required.SeatHoldPort`)

```kotlin
interface SeatHoldPort {
    fun hold(scheduleId: ScheduleId, seatIds: List<SeatId>, owner: HoldToken, ttl: Duration, now: Instant): HoldResult
    fun release(scheduleId: ScheduleId, seatIds: List<SeatId>, owner: HoldToken, now: Instant)   // 소유자일 때만 해제
    fun markSold(scheduleId: ScheduleId, seatIds: List<SeatId>, owner: HoldToken, now: Instant)  // DB 확정 커밋 후 호출
    fun markAvailable(scheduleId: ScheduleId, seatIds: List<SeatId>, now: Instant)                // 확정 취소 후 호출
    fun initSnapshot(scheduleId: ScheduleId, seatIds: List<SeatId>)                               // 이미 있는 필드는 덮어쓰지 않음(HSETNX)
}
sealed interface HoldResult {
    data object Held : HoldResult
    data class Unavailable(val seatIds: List<SeatId>) : HoldResult
}
```

### 3. Lua 스크립트 (`core/src/main/resources/redis/booking/*.lua`)

- `hold.lua`: 모든 좌석에 대해 스냅샷이 `SOLD`이거나 선점 키가 있으면, 아무것도 쓰지 않고 해당 좌석 목록을 반환한다. 전부 비어 있으면 모든 선점 키를 `SET PX`로 쓰고 스냅샷을 `HELD:<exp>`로 바꾼 뒤 `XADD` 1건을 남긴다. **전부 성공하거나 전부 실패한다.**
- `release.lua`: 선점 키 값이 owner와 같은 좌석만 `DEL`하고 스냅샷을 `AVAILABLE`로 바꾼 뒤 `XADD`
- `mark-sold.lua`: 스냅샷을 `SOLD`로 바꾸고, owner가 일치하는 선점 키를 `DEL`한 뒤 `XADD`
- `mark-available.lua`: 스냅샷을 `AVAILABLE`로 바꾸고 `XADD`
- 현재 시각은 인자로 받는다(`Clock`에서 얻음). Redis `TIME`을 쓰지 않는다. 이유: 테스트에서 시각을 고정한다.
- `RedisScript`/`DefaultRedisScript`를 빈으로 한 번만 로드하고 `StringRedisTemplate.execute`로 실행한다.

### 4. 어댑터와 연결

- `booking.adapter.required.redis.RedisSeatHoldAdapter`
- step 1의 `RegisterScheduleSeatsUseCase` 구현: 사본을 새로 저장했을 때 **DB 커밋 후** `initSnapshot`을 호출한다(`TransactionSynchronization.afterCommit` 등). 다시 수신해도 HSETNX라 안전하다.

### 5. 테스트 (`core/src/test/kotlin/io/github/ticketing/booking/adapter/required/redis/`)

Testcontainers Redis를 쓴다(`IntegrationTestSupport` 또는 Redis만 띄우는 가벼운 설정). 테스트마다 키를 정리한다.

- 선점 성공 후 TTL 확인, 다른 owner의 해제 무시, 소유자 해제, 판매 후 TTL이 지나도(키 삭제로 재현) 재선점 불가, 판매 해제 후 재선점 가능
- 스냅샷 값과 Stream 레코드 형식
- **동시성**: 스레드 50개(`Executors.newVirtualThreadPerTaskExecutor()` + `CountDownLatch`로 동시 시작)가 같은 좌석을 선점하면 성공은 정확히 1건이다. 좌석이 겹치는 다중 선점(예: {1,2}와 {2,3})은 한쪽만 성공하고, 실패한 쪽의 좌석은 하나도 선점되지 않는다.

### 6. 로드맵

`docs/roadmap.md` M1-3의 "Redis 좌석 선점: Lua 원자적 다중 선점, TTL 5분, 최대 4매"를 `[x]`로 바꾼다. "좌석 상태 변경을 Redis Streams로 발행"은 step 4에서 흐름에 모두 연결된 뒤 체크한다.

## Acceptance Criteria

```bash
test -n "$(fd -e lua . core/src/main/resources/redis/booking)"
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.booking.adapter.required.redis.*"
rg -q 'HELD:' docs/design.md
rg -q '^- \[x\] Redis 좌석 선점' docs/roadmap.md
```

## 검증 절차

1. 위 AC를 실행하고, 실패하면 원인을 수정한다.
2. `phases/2-booking/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "생성/수정한 파일 경로와 핵심 결론을 2줄·200자 이내로"`
   - 해결할 수 없는 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 선점을 `SETNX`나 `GET`/`DEL` 같은 여러 명령으로 나눠 보내지 마라. 이유: 원자성이 깨져 다중 좌석의 전부 성공·전부 실패를 보장하지 못한다.
- 소유자 확인 없이 선점 키를 `DEL`하지 마라. 이유: 다른 사용자의 선점을 풀 수 있다.
- 스냅샷의 `HELD`만 보고 선점 가능 여부를 판단하지 마라. 이유: TTL 만료가 스냅샷에 반영되지 않는다.
- 해시 태그 없는 키를 만들지 마라. 이유: 위 1절
- 테스트에서 `Thread.sleep`으로 TTL 만료를 기다리지 마라. 키를 직접 삭제하거나 짧은 TTL과 Awaitility를 쓴다.
- 검색은 `rg`/`fd`를 우선 쓴다. 이유: `.gitignore`를 존중해 `build/` 결과가 섞이지 않고 출력이 짧다. 사용 가능한 도구는 Bash, Read, Write, Edit뿐이다.
- 기존 테스트를 깨뜨리지 마라.
- git commit 하지 마라. 이유: execute.py가 AC 통과를 확인한 뒤 step 단위로 커밋한다.
