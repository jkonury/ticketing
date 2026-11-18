# ADR 0039: Kafka는 Modulith 이벤트 외부화에만 쓰고 좌석 스트림은 Redis Streams를 유지한다

- 상태: 합의
- 작성일: 2026-11-18
- 대체: 없음
- 대체됨·일부 변경: 없음
- 관련: [ADR 0009](0009-realtime-redis-only.md), [ADR 0010](0010-events-modulith-and-redis-streams.md), [ADR 0017](0017-payment-owns-payment-events.md), [ADR 0038](0038-queue-stays-on-redis-not-kafka.md), [M6 계획](../plans/m6-msa.md)

## 맥락

M6에서 `payment`·`settlement`를 별도 배포 단위로 분리하면 모듈 간 Modulith 이벤트가 프로세스 경계를 넘는다. 로드맵은 "Modulith 이벤트 외부화로 Kafka 도입, 앱 간 Redis Streams 대체 범위 결정"을 요구한다.

공식 문서([Spring Modulith 이벤트](https://docs.spring.io/spring-modulith/reference/events.html))에서 확인한 사실은 다음과 같다.

- `spring-modulith-events-kafka`와 `@Externalized("토픽::키")`로 이벤트를 Kafka에 보낸다. 외부화는 이벤트 발행 레지스트리(`event_publication`) 위에서 동작한다.
- 브로커 전송이 실패하면 항목이 미완료로 남아 재제출된다. 문서는 기본 방식에 outbox의 핵심 기능이 빠져 있다고 경고한다. 전달 보장은 최소 1회로 보고 소비자를 멱등으로 둔다.
- 와이어 포맷이 JSON이라는 명시는 없다. 직렬화 형식은 프로젝트가 고정해야 한다.
- `spring.modulith.events.externalization.serialize-externalization=true`는 재제출 시 순서가 뒤바뀌는 것을 막는다.

## 검토한 대안

- (A) Modulith 이벤트 외부화에만 Kafka를 쓴다. core→realtime 좌석 변경 스트림은 Redis Streams를 유지한다.
- (B) 좌석 변경 스트림도 Kafka로 대체한다.
- (C) A로 시작하고 realtime 구독만 나중에 재검토한다.

| 안 | 장점 | 단점 |
|---|---|---|
| A | ADR 0009·0010·0038과 정합. realtime은 Redis만 본다. 변경 범위가 작다 | 메시징 수단이 두 가지로 남는다 |
| B | 수단이 하나로 통일된다 | realtime에 Kafka 클라이언트가 생겨 ADR 0009를 바꿔야 한다. 최근 약 1만 건 재생과 스냅샷 모델을 다시 설계해야 하고 리액티브 클라이언트 선택이 필요하다 |
| C | 위험이 작다 | 결정을 미루는 셈이다. A와 실질 차이가 없다 |

## 결정

**(A)**. Kafka는 `core`·`payment`·`settlement` 사이의 도메인 이벤트 외부화에만 쓴다.

- 좌석 변경 스트림(`booking:seat-events:{scheduleId}`)과 스냅샷은 Redis Streams를 유지한다. 짧게 재생하고 버리는 팬아웃이라 Redis가 맞고, [ADR 0038](0038-queue-stays-on-redis-not-kafka.md)과 같은 논리다.
- 외부화 대상은 프로세스 경계를 넘는 이벤트로 한정한다(계획서의 이벤트 표). 같은 JVM 안에 남는 이벤트(`booking`↔`coupon` 등)는 Modulith 이벤트 그대로 둔다.
- 소비자는 멱등으로 만들고, 이벤트 페이로드는 JSON과 버전 필드로 고정한다.
- `serialize-externalization` 사용 여부와 outbox 모드(Namastack, JobRunr) 도입 여부는 계획서 2단계에서 측정·검증 후 정한다.
- B는 좌석 스트림에 Redis로 풀 수 없는 요구가 생기면 새 ADR로 다시 검토한다.

## 결과

- 얻는 것: ADR 0009의 "realtime은 Redis만 사용"과 단일 실시간 경로 유지, 변경 범위 최소화.
- 감수하는 것: 메시징 수단 두 가지(Kafka, Redis Streams)를 운영한다. 최소 1회 전달이므로 소비자 멱등 검증이 필요하다.
- 바뀌는 문서·지침: 구현 단계(계획서 2단계)에서 `docs/architecture/events.md`의 이벤트 종류 표와 `docs/architecture.md` §3을 고친다. 지금은 바꾸지 않는다.

## 변경 이력

- 2026-11-18: 작성(합의)
