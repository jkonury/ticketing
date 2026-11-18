# ADR 0043: 모놀리스 상태의 Kafka 외부화(M6 2단계) 구현 결정

- 상태: 합의
- 작성일: 2026-11-18
- 대체: 없음
- 대체됨·일부 변경: 없음
- 관련: [ADR 0039](0039-kafka-externalizes-modulith-events-only.md), [ADR 0042](0042-contract-module-keeps-packages-and-json-compat-rules.md), [M6 계획](../plans/m6-msa.md), [events.md](../architecture/events.md#kafka-외부화-대상-m6)

## 맥락

[ADR 0039](0039-kafka-externalizes-modulith-events-only.md)는 Kafka를 Modulith 이벤트 외부화에만 쓰기로 했고, 로컬 리스너 처리·직렬화·실패 처리·`serialize-externalization`·브로커 기동 방식은 2단계에서 정하도록 남겼다. 2단계는 payment 분리 전에 core 안에서 결제 계약 이벤트 5종을 Kafka로 먼저 돌려 본다.

## 검토한 대안

| 결정 | 선택 | 검토한 대안 | 선택 이유 |
|---|---|---|---|
| 로컬 리스너 전환 | 로컬 `@ApplicationModuleListener`를 제거하고 같은 클래스에서 `@KafkaListener`로 교체 | 프로퍼티 토글, 프로필 분기 | 3단계 분리 후에도 코드가 그대로 쓰인다. 토글은 이중 처리 위험과 테스트 조합을 늘린다 |
| 본문 직렬화 | JSON 고정, 타입 헤더 없음. 소비자는 리스너 파라미터 타입으로 역직렬화 | 타입 헤더 사용 | 헤더에 클래스명이 들어가면 분리 후 패키지·모듈이 달라질 때 깨진다 |
| 골든 일치 확인 | 브로커로 나간 메시지를 `String`으로 소비해 `contract` 골든 JSON과 트리 비교 | 직렬화기 단위 테스트만 | 실제 와이어 형식을 검증한다. ADR 0042의 골든 파일을 재사용한다 |
| 컨슈머 실패 처리 | `DefaultErrorHandler` + 지수 백오프 재시도(초안 3회, 1s 시작) 후 `<토픽>.dlt` | 재시도 후 폐기 | 폐기하면 결제 결과가 유실되어 예매가 `PENDING`에 남는다. DLT 적재 건수를 지표로 노출하고 runbook에 재처리 절차를 둔다 |
| 소비자 멱등 보강 | `PaymentRequested`의 유니크 충돌을 정상 중복으로 처리하는지 확인, `RefundRequested`의 환불 진행 중 중복 도착 경합 테스트 추가 | 없음 | events.md가 "2단계에서 추가 필요"로 표시한 항목이다 |
| `serialize-externalization`·outbox | 둘 다 채택하지 않음(잠정) | 둘 중 하나 채택 | 키가 `orderId`이고 소비자가 상태 전이 검사로 순서 역전을 흡수한다. 직렬화는 처리량을 낮춘다. 2-5 측정에서 확정 지연이 나쁘거나 순서 문제가 나오면 재검토한다 |
| Kafka 기동 | compose **기본 서비스**(프로필 분리 안 함). 공식 `apache/kafka` KRaft 단일 브로커, 호스트 포트 `127.0.0.1:19092`. 테스트는 Testcontainers `KafkaContainer` 공유 | compose 프로필로 분리 | 2단계 이후 core는 Kafka 없이 결제가 되지 않는다 |
| 파티션·브로커 | 토픽당 3, 단일 브로커 | 브로커 3대 | 로컬 자원 한계. 측정 후 조정 |

## 결정

위 표의 선택을 따른다.

**[M6 계획 4절](../plans/m6-msa.md#4-위험과-대응)과 달라진 점**: 4절은 "단일 브로커 KRaft로 시작하고 compose 프로필로 분리"라고 적었다. 프로필 분리는 켜고 끌 수 있는 부가 스택(관측 스택)에 맞는 방식이다. 2단계 이후 결제 흐름이 Kafka를 거치므로 Kafka는 PostgreSQL·Redis와 같은 필수 인프라이며 기본 서비스로 둔다. 4절 문구는 이에 맞춰 고쳤다.

## 결과

- 얻는 것: 분리 전에 Kafka 경로를 같은 프로세스에서 검증하고, 3단계 분리 때 소비자 코드를 바꾸지 않는다.
- 감수하는 것: `docker compose up -d`가 Kafka까지 띄운다(로컬 메모리 증가). Kafka가 내려가면 결제·예매 확정이 지연된다(미완료 이벤트로 남아 복구 후 재전달).
- 후속: `serialize-externalization`·outbox 최종 여부는 2-5 측정 후 이 ADR을 갱신한다.

## 변경 이력

- 2026-11-18: 작성(합의)
