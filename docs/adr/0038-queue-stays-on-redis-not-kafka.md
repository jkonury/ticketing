# ADR 0038: 대기열 저장소는 Redis를 유지하고 Kafka로 옮기지 않는다

- 상태: 제안
- 작성일: 2026-11-18
- 대체: 없음
- 대체됨·일부 변경: 없음
- 관련: [ADR 0008](0008-queue-in-realtime-app.md), [ADR 0009](0009-realtime-redis-only.md), [ADR 0010](0010-events-modulith-and-redis-streams.md)

## 맥락

대기열을 Redis(Sorted Set + Lua)에서 Kafka로 옮기면 어떤 이득과 비용이 있는지 검토했다. 현재 구현은 `realtime/src/main/resources/redis/queue/`의 `register.lua`, `admit.lua`, `touch-seen.lua`와 `QueueService`, `AdmissionService`다. 로드맵상 Kafka는 M6에서 Modulith 이벤트 외부화 용도로만 계획되어 있고, 대기열 용도는 아니다.

현재 대기열이 Redis에 기대는 기능은 다음과 같다.

| 기능 | 현재 구현 | Kafka로 대체 가능 여부 |
|---|---|---|
| 중복 없는 등록(재클릭 시 기존 순번 반환) | `register.lua`가 "입장했나 → 대기 중인가 → 신규 등록"을 원자적으로 판정 | 불가. 로그에서 특정 회원 존재 여부를 조회할 수 없다 |
| 내 순번 조회(SSE가 주기적으로 호출) | `ZRANK` O(log N) | 부분 가능. offset 차이로 근사만 가능 |
| 이탈 처리(명시적 이탈, 하트비트 만료) | `ZREM`, `queue:seen` ZSET | 불가. 로그 중간 항목을 지울 수 없다 |
| 입장 배치에서 이탈자 건너뛰기 | `admit.lua`가 seen 확인과 제거를 원자적으로 처리 | 부분 가능. 컨슈머가 외부 저장소를 조회해야 한다 |
| 입장 토큰 검증(Gateway) | `queue:admission:<token>` Hash + TTL | 불가. 키 단위 조회와 TTL이 없다 |

## 검토한 대안

- (a) Redis 유지(현행)
- (b) 대기열 전체를 Kafka로 이전
- (c) 하이브리드: 등록 이벤트를 Kafka에 남기고, 순번·이탈·토큰은 Redis에 둔다

### 관점별 비교

**구현**
- (b)는 Lua 원자성을 잃는다. 중복 확인(Redis) → produce → 소비 → 토큰 기록(Redis) → offset commit으로 흩어져 단계마다 실패 지점이 생긴다.
- 컨슈머는 at-least-once라 commit 전 장애 시 같은 회원에게 토큰이 두 번 발급될 수 있다. 발급을 멱등하게 만들어야 하고, 그 확인은 다시 Redis가 맡는다. Kafka 트랜잭션(EOS)은 Redis 쓰기에 적용되지 않는다.
- 초당 N명 속도 제어는 현재 `@Scheduled` + `ZRANGE` 배치로 단순하다. Kafka는 `max.poll.records`, `pause`/`resume`, 수동 commit 조합이 필요하고, 의도적으로 느린 소비는 `max.poll.interval.ms` 리밸런스와 충돌한다.
- `realtime`은 WebFlux + Coroutines다. Spring Kafka 리스너는 블로킹 스레드 모델이 기본이므로 리액티브 클라이언트 선택을 공식 문서로 확인해야 한다.

**성능**
- 등록 쓰기는 Redis가 1ms 미만, Kafka는 `acks=all` 기준 수 ms다. 1만 명 규모 급증은 Redis 단일 노드로 충분하다.
- 순번 조회가 현재 부하의 핵심이다. offset 차이로 O(1) 근사가 가능하지만, 이는 Redis에서도 `queue:seq` 차이(내 seq − 입장한 seq)로 같은 효과를 낼 수 있어 Kafka만의 이점이 아니다. 두 방식 모두 이탈자가 빠지지 않아 순번은 상한 근사가 된다.
- 컨슈머 리밸런스(배포, 스케일)마다 입장 처리가 수 초 멈춘다.

**확장성**
- 회차 내 순서 보장을 위해 key를 scheduleId로 잡으면 한 회차가 한 파티션에 몰린다. Redis Cluster의 `{scheduleId}` 해시 태그와 같은 성격이다.
- Kafka 고유의 문제로 Head-of-line 블로킹이 있다. 여러 회차가 같은 파티션에 해시 충돌하면, 한 회차의 입장 속도 제한이 다른 회차를 막는다. 회피하려면 회차별 토픽·파티션 직접 할당이 필요한데 메타데이터와 관리 비용이 커진다.

**내구성·정합성 (Kafka의 실질적 이점)**
- Redis는 장애 시 대기열을 잃을 수 있다(AOF everysec이면 최대 약 1초분). Kafka는 복제 로그라 등록 순서가 보존되고 감사 로그로도 쓸 수 있다.
- 다만 대기열은 수 분짜리 휘발성 상태이고, 유실되면 재진입으로 복구할 수 있다. 좌석 확정의 최종 방어선은 DB 유니크 제약이므로 내구성의 가치가 결제·예매 데이터만큼 크지 않다.

**유지보수·운영**
- 내구성 있는 복제를 얻으려면 KRaft 브로커 3대가 필요해 로컬 compose 자원과 Testcontainers 테스트 시간이 늘어난다.
- 관측 대상이 consumer lag, 리밸런스, ISR, 파티션 쏠림으로 늘어난다. Redis 지표도 그대로 남는다.
- 비동기 컨슈머는 `Clock` 고정과 `runTest`만으로 검증하기 어렵고 폴링 대기가 필요하다. 현재 Lua 기반 테스트는 결정적이다.
- 장애 지점이 Redis 하나에서 Redis와 Kafka 둘로 늘고, 둘 사이 불일치(produce 성공, Redis 기록 실패)의 보정 로직이 필요하다.
- ADR 0009(realtime은 Redis만 사용)를 바꿔야 한다.

## 결정

**(a)** Redis를 유지한다. 대기열은 원자적 상태 전이, 임의 삭제, 키 단위 조회, TTL이 핵심인 문제라 Redis 자료구조가 맞고, Kafka로 옮겨도 이 기능들 때문에 Redis가 남아 저장소가 하나에서 둘로 늘어날 뿐이다.

- 순번 조회 부하가 문제가 되면 Kafka가 아니라 Redis 안에서 seq 차이 기반 근사 순번과 회차별 head 브로드캐스트를 먼저 검토한다.
- Kafka는 계획대로 M6에서 Modulith 이벤트 외부화 용도로만 도입한다.
- (c)는 감사 로그나 급증 버퍼의 구체적 요구가 생길 때 새 ADR로 다시 검토한다.

## 결과

- 얻는 것: Lua 원자성과 결정적 테스트, 단일 저장소 운영, ADR 0009 규칙 유지.
- 감수하는 것: Redis 유실 시 대기열 순서가 복구되지 않는다(재진입으로 복구). 등록 순서의 감사 로그가 없다.
- 바뀌는 문서·지침: 없음. 새 ADR 목록(`docs/adr/README.md`)만 갱신한다.

## 변경 이력

- 2026-11-18: 작성(제안)
