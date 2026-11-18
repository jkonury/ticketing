# ADR 0042: 계약 이벤트는 기존 패키지를 유지한 채 `contract` 모듈로 옮기고 JSON 형식은 골든 파일로 고정한다

- 상태: 합의
- 작성일: 2026-11-18
- 대체: 없음
- 대체됨·일부 변경: 없음
- 관련: [ADR 0017](0017-payment-owns-payment-events.md), [ADR 0039](0039-kafka-externalizes-modulith-events-only.md), [M6 계획](../plans/m6-msa.md), [events.md §1](../architecture/events.md#1-공통-규칙)

## 맥락

payment·settlement를 분리하면 결제 계약 이벤트 5종(`PaymentRequested`, `RefundRequested`, `PaymentSucceeded`, `PaymentFailed`, `PaymentRefunded`)과 `BookingConfirmed`·`BookingCancelled`가 프로세스 경계를 넘는다. 두 쪽이 같은 클래스를 컴파일해야 하고 JSON 형식이 바뀌면 안 된다. Modulith 발행 레지스트리 `event_publication`은 `event_type`(FQCN)과 `serialized_event`(JSON)를 저장하며, 미완료 행은 재시작 때 재발행한다(`republish-outstanding-events-on-restart: true`). Modulith는 Boot의 Jackson 3 `JsonMapper`로 직렬화한다(`JacksonEventSerializer`).

## 검토한 대안

- (A) 패키지(`io.github.ticketing.payment`, `io.github.ticketing.booking`)를 유지한 채 클래스만 `contract` jar로 옮긴다.
- (B) `io.github.ticketing.contract.*` 새 패키지로 옮긴다.

| 안 | 장점 | 단점 |
|---|---|---|
| A | import·FQCN이 그대로라 `event_publication` 미완료 행이 호환된다. ADR 0017의 의존 방향(`booking → payment`)과 Modulith 검증이 그대로다 | 같은 패키지가 core와 contract jar에 나뉜다(split package). 클래스패스 방식이라 동작에는 문제가 없다 |
| B | 패키지가 명확히 갈린다 | FQCN이 바뀌어 미완료 행을 SQL로 이전해야 하고, Modulith가 `contract`를 모듈로 인식해 의존 규칙을 다시 정리해야 한다 |

버전 표시도 둘을 비교했다. 페이로드의 `schemaVersion` 필드는 명시적이지만 모든 이벤트 클래스·발행부·문서를 바꿔야 한다. 필드 없이 규칙만 두면 변경이 없고 골든 파일 테스트로 강제할 수 있다.

## 결정

**(A)** 와 **버전 필드 없이 하위 호환 규칙만 둔다**.

- `contract` 모듈은 Kotlin·JDK 타입만 쓴다(Spring·Jackson 의존 없음). 직렬화 테스트에만 Jackson을 쓴다.
- JSON 형식은 [events.md §1](../architecture/events.md#1-공통-규칙)의 규칙과 `contract/src/testFixtures/resources/contract-events/*.json` 골든 파일로 고정한다. `ContractEventSerializationTests`(contract)와 `ContractEventPublicationSerializationTests`(core, 실제 Modulith `EventSerializer`)가 직렬화·역직렬화·알 수 없는 필드 허용을 검증한다. 골든 파일과 샘플은 testFixtures로 공유한다.
- 깨지는 변경(필드 삭제·이름·타입 변경)은 같은 클래스를 고치지 않고 새 이벤트 타입(예: `PaymentSucceededV2`)을 추가해 일정 기간 둘을 함께 발행한다.

## 결과

- 얻는 것: 동작·DB 호환성 변화 없이 분리 준비가 끝난다.
- 감수하는 것: split package. payment 분리 후 `contract`만 공유하는 구조가 되면 그때 패키지 이전이 필요한지 다시 본다.
