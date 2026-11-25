---
name: event-contract-change
description: contract 모듈의 프로세스 간 이벤트(BookingConfirmed, PaymentRequested 등)를 추가·변경하거나 core·payment의 Kafka 발행·수신 경로를 바꿀 때 쓰는 절차. 골든 파일, 직렬화 테스트, 이벤트 카탈로그, Modulith 검증을 빠짐없이 맞춘다.
---

# 계약 이벤트 변경 절차

규범은 [contract 작성 지침](../../../docs/rules/backend-contract.md)과 [이벤트 카탈로그](../../../docs/architecture/events.md)를 따른다. 이 문서는 순서만 정한다.

## 1. 변경 분류

- 필드 추가(선택값) 등 하위 호환 변경인지, 이름·타입 변경이나 필수 필드 추가 같은 깨지는 변경인지 먼저 정한다. 깨지는 변경은 기존 타입을 고치지 않고 새 이벤트 타입으로 도입한다(ADR 0042).
- 발행자와 수신자를 찾는다: `rg -n '<이벤트명>' core/src payment/src --glob '*.kt'`. Kafka 외부화 대상이면 core `BookingEventExternalizationConfig`, 수신 측 `@KafkaListener`(core `PaymentResultListener`, payment `PaymentRequestListener`·`RefundRequestListener`)를 확인한다.

## 2. 변경 순서

1. `contract/src/main/kotlin/io/github/ticketing/<소유 모듈>/`의 이벤트 클래스
2. 골든 파일 `contract/src/testFixtures/resources/contract-events/<이벤트명>.json`
3. 발행자 → 수신자 순으로 core·payment 코드. 수신자는 중복 전달에 멱등해야 한다
4. `docs/architecture/events.md`(카탈로그, Kafka 외부화 표)와 필요하면 `docs/architecture.md` 이벤트 목록

## 3. 검증

```bash
./scripts/agent/test/verify-changed.sh            # 변경 경로에 맞는 검사를 고른다
./scripts/agent/test/run-tests.sh :contract:test
./scripts/agent/test/run-tests.sh :core:test --tests "*ContractEventPublicationSerializationTests" --tests "*PaymentEventExternalizationTests" --tests "*ModularityTests"
./scripts/agent/test/run-tests.sh :payment:test --tests "*ContractEventPublicationSerializationTests" --tests "*PaymentEventExternalizationTests"
```

- 모듈 의존이 바뀌었으면 `./gradlew :core:modulithDocs`로 `docs/modulith/`를 갱신해 같은 변경에 넣는다.
- 결제 흐름이 바뀌었으면 E2E를 실행한다([E2E 실행](../../../docs/rules/backend-testing.md#e2e부하ci-실행)).
- 마지막으로 [이벤트 추가 체크리스트](../../../docs/architecture/events.md#5-이벤트를-추가할-때-체크리스트)를 대조한다.
