# 데이터 — Redis (§5)

> [← 아키텍처 개요](../architecture.md) · 범위: §5 데이터 중 Redis 용도와 키 형식
> 함께 보기: [erd.md](./erd.md), [flows.md](./flows.md)

## Redis 용도

| 용도 | 자료구조 | 소유 |
|---|---|---|
| 좌석 임시 선점 | `SET NX` + TTL, 다중 좌석은 Lua | 코어 `booking` |
| 좌석 현황 스냅샷 | Hash 등 (회차별) | 코어가 유지, 실시간 앱이 읽음 |
| 좌석 상태 변경 전달 | Streams | 코어 발행, 실시간 앱 구독 |
| 대기열 | Sorted Set (`queue:waiting:{<scheduleId>}` 등) | 실시간 앱 |
| 입장 토큰 | `queue:admission:<token>` Hash (`memberId`, `scheduleId`), TTL; 조회 키 `queue:admitted:{<scheduleId>}:<memberId>` | 실시간 앱 발급, Gateway 검증 |
| SSE 티켓 | `sse:ticket:<ticket>` String (회원 ID), TTL 30초 | 코어 `member` 발급, Gateway 1회 소비 |
| rate limit | SCG `RedisRateLimiter` 내부 키 | Gateway |
| 조회 캐시 | 공연·회차 정보 | 코어 `catalog` |
| 불변 좌석 사본 캐시 | 회차별 Hash, TTL 6시간 | 코어 `booking` |
| 선착순 쿠폰 재고·중복 방지 | `coupon:{<definitionId>}:stock`(String, 남은 수량), `coupon:{<definitionId>}:claimed`(Set, 받은 회원 ID). Lua로 원자 처리 | 코어 `coupon` |
| BFF 세션 | `bff:session:<sessionId>` String(JSON), TTL 14일 | `web` BFF |
| BFF 토큰 갱신 락 | `bff:refresh-lock:<sessionId>` String, `SET NX PX` | `web` BFF |

`catalog` 캐시는 트랜잭션 안의 변경을 커밋 뒤 적용하고, Redis 쓰기·삭제 완료를 기다린다(`RedisCacheWriter.immediateWrites`).

### 대기열 키 형식 (realtime)

realtime 소유 키는 `{scheduleId}` 해시 태그로 같은 슬롯에 둔다(`queue:admission:<token>`, `queue:active` 제외).

| 키 | 자료구조 | 내용 |
|---|---|---|
| `queue:seq:{<scheduleId>}` | String (`INCR`) | 대기 순번 일련번호 |
| `queue:waiting:{<scheduleId>}` | Sorted Set | 회원 ID를 member로, 일련번호를 score로 저장 |
| `queue:seen:{<scheduleId>}` | Sorted Set | 회원 ID를 member로, 마지막 신호 epoch ms를 score로 저장 |
| `queue:admitted:{<scheduleId>}:<memberId>` | String, 토큰 TTL | 해당 회원의 입장 토큰 조회 |
| `queue:admit-lock:{<scheduleId>}` | String, PX | 입장 주기 동안 회차별 처리 락 |
| `queue:active` | Set | 대기자가 있는 회차 ID |
| `queue:admission:<token>` | Hash, TTL | `memberId`, `scheduleId`; Gateway 검증 계약은 [ADR 0020](../adr/0020-gateway-verification-contracts.md) 참조 |

회차별 Lua 처리는 같은 `{scheduleId}` 해시 태그를 가진 키만 대상으로 한다. `queue:admission:<token>`은 해시 태그가 없으므로 Lua 밖에서 기록한다.

### 좌석 선점 키 형식 (코어 `booking`, realtime이 읽음)

모든 키에 `{scheduleId}` 해시 태그를 넣어 Lua가 다루는 키를 같은 슬롯에 둔다.

| 키 | 자료구조 | 내용 |
|---|---|---|
| `booking:hold:{<scheduleId>}:<seatId>` | String, PX TTL | 값은 선점 소유자(`HoldToken`) |
| `booking:seats:{<scheduleId>}` | Hash | field `seatId` → `AVAILABLE` / `HELD:<만료 epoch ms>` / `SOLD` |
| `booking:seat-events:{<scheduleId>}` | Stream, `MAXLEN ~ 10000` | `seatIds`(쉼표 구분), `status`, `holdExpiresAt`(HELD일 때), `at`(epoch ms) |

- 스냅샷의 `HELD:<만료 시각>`: 선점 키 TTL이 끝나도 Redis는 알려 주지 않으므로 읽는 쪽이 만료를 직접 판정한다.
- 선점 가능 여부의 판단 기준은 스냅샷이 아니라 선점 키와 `SOLD`다.
- 선점·해제·판매·판매 해제는 각각 Lua 스크립트 한 번으로 처리하며, 같은 스크립트에서 스냅샷과 Stream을 갱신한다.
- DB 커밋 뒤 Redis 반영이 실패하면 경고 로그만 남긴다. 선점 키는 TTL로 정리되고 스냅샷 보정은 M2 Batch가 한다([6.4절](./flows.md#64-결제-이벤트-기반)).

### 불변 좌석 사본 캐시 (코어 `booking`)

`booking:seat-copies:v1:{<scheduleId>}` Hash의 field는 좌석 ID, value는 `grade`, `label`, `price` JSON이다.
판매 상태 스냅샷과 별개다. 요청 좌석만 HMGET하고 누락·손상은 DB 조회로 보완한다.
등록·조회 결과는 커밋 후 Lua로 HSET과 EXPIRE(6시간)를 함께 적용한다. 채울 때 TTL을 갱신하며
hit은 TTL을 연장하지 않는다. 빈 결과는 저장하지 않고 Redis 오류는 DB로 fallback한다.
좌석 단위 hit/miss는 `ticketing.booking.seat.copy.cache` 카운터의 `result` 태그로 기록한다.
자세한 대안과 불변 조건은 [ADR 0025](../adr/0025-booking-seat-copy-cache.md)를 본다.

### 선착순 쿠폰 키 (코어 `coupon`)

두 키는 `{definitionId}` 해시 태그로 같은 슬롯에 둔다. 스크립트는 `core/src/main/resources/redis/coupon/`에 있다.

| 스크립트 | 동작 |
|---|---|
| `claim.lua` | 재고 키가 없으면 `NOT_INITIALIZED`, 이미 받았으면 `DUPLICATE`, 재고가 0이면 `SOLD_OUT`, 아니면 `DECR` + `SADD` 후 `OK` |
| `unclaim.lua` | DB INSERT 실패나 유니크 위반 시 재고 복원과 Set 제거(보상) |
| `init.lua` | 재고 키가 없을 때만 초기화 |
| `reconcile.lua` | DB 기준 남은 수량과 발급 회원으로 두 키를 재구성 (`reconcileCouponStockJob`) |

Redis는 빠른 거름망이고 최종 방어선은 DB `(definition_id, member_id)` 유니크 제약이다. `limited-claim.strategy`가 `redis`가 아니면 DB 조건부 UPDATE(`remaining_quantity`)로 발급하며 이 키를 쓰지 않는다. [ADR 0037](../adr/0037-limited-coupon-claim-redis-lua.md)과 [flows.md](./flows.md#66-선착순-발급)를 본다.
