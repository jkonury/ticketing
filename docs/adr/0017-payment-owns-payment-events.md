# 0017. 결제 계약 이벤트는 payment 모듈이 소유

- 상태: 수락
- 작성일: 2026-11-10
- 합의일: 2026-11-10
- 결정 주체: 사용자 합의
- 대체: 없음
- 대체됨·일부 변경: 없음
- 관련: [설계](../architecture.md), [ADR 0010](0010-events-modulith-and-redis-streams.md), [ADR 0011](0011-event-driven-payment.md)

## 배경

`booking`은 결제를 요청하고 결과를 받으며, `payment`는 요청을 처리하고 결과를 알린다. 이벤트를 발행하는 쪽이 자기 모듈 루트에 이벤트를 두면 `booking`은 `PaymentRequested`, `RefundRequested`를, `payment`는 `PaymentSucceeded`, `PaymentFailed`, `PaymentRefunded`를 소유하게 되고 서로의 이벤트를 import하는 순환이 생긴다. `ApplicationModules.verify()`가 순환 의존을 실패로 처리하므로 M1 구현 전에 소유 방향을 정해야 한다.

## 결정

1. 결제 계약 이벤트 5종(`PaymentRequested`, `RefundRequested`, `PaymentSucceeded`, `PaymentFailed`, `PaymentRefunded`)은 모두 `payment` 모듈 루트 패키지에 둔다.
2. 의존 방향은 `booking → payment` 하나다. `payment`는 예매를 모르고 `orderId`만 안다.

## 검토한 대안

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| 각 모듈이 자기가 발행하는 이벤트를 소유하고 순환을 허용 | `ApplicationModules.verify()`가 실패한다. 순환을 허용하면 MSA 분리 시 두 서비스가 서로의 계약에 묶인다. |
| 공용 이벤트 모듈을 따로 둔다 | 전역 공유 모듈이 되어 모듈 경계의 의미가 흐려지고, 이벤트가 몇 개 안 되는 현 단계에서 과하다. |

## 결과

- 얻는 것: 순환 없는 단방향 의존, 결제 계약이 한 모듈에 모여 Saga 전환 시 계약 경계가 분명하다.
- 감수하는 것: `booking`이 `payment`에 컴파일 의존한다. `payment`가 이벤트 필드를 바꾸면 `booking`이 영향을 받는다.
- 바뀌는 문서·지침: `docs/architecture.md` 4절 모듈 규칙, `docs/adr/README.md` 목록.

## 검증

`ModularityTests`(`ApplicationModules.verify()`)가 모듈 순환을 검사한다.

## 변경 이력

- 2026-11-10: 작성, 합의
