# M1-5 payment 구현 지시서

이 디렉터리는 **구현 전 계획**이다. 실행 목록은 [index.json](index.json)이며 모든 step은 pending이다. 현재 문서 작성 세션에서는 코드를 구현하거나 완료 체크하지 않는다.

| 순서 | 지시서 | 주된 검증 |
|---|---|---|
| 0 | [payment-domain-persistence](step00-payment-domain-persistence.md) | 상태 전이·멱등성, 저장·유니크·버전 |
| 1 | [payment-pg-adapter](step01-payment-pg-adapter.md) | HTTP 계약·결과 분류·타임아웃 |
| 2 | [payment-application](step02-payment-application.md) | 트랜잭션 경계·요청/웹훅/환불 처리 |
| 3 | [payment-inbound-adapters](step03-payment-inbound-adapters.md) | 레지스트리·서명·HTTP·기존 테스트 격리 |
| 4 | [payment-flow-verification](step04-payment-flow-verification.md) | 예매 확정·자동 환불・원 발행 재처리 |

각 step은 별도 세션에서 위 순서로 실행한다. 이전 step AC 통과 전에 다음 step을 시작하지 않는다. 문서와 index의 AC는 동일하게 유지한다. 테스트 필터는 사용하지 않는다. 구조 AC의 신규 파일 확인은 현재 트리에서 실패해야 하며, 기존 의존 금지 검사는 유지해야 할 회귀 방어다.

기술 결정은 [ADR 0018](../../docs/adr/0018-payment-retry-boundaries.md)에 기록했다. 미확정 결제·환불 거절은 미완료 이벤트로 남기고 같은 키로 재처리한다. 주기적 재처리 스케줄러와 M2 대사는 이번 범위가 아니다.

## 이번 실행 목록에서 제외한 작업

[기존 notification·모듈 테스트 초안](step02-notification-module-tests.md)은 보관 자료이며 index에 등록하지 않는다. 해당 초안은 이전 step 번호와 검증 필터를 담고 있으므로 그대로 실행하지 않는다. M1-6·M1-7을 진행할 별도 세션에서 지시서와 실행 목록을 먼저 정리한다.

상위 실행 목록의 다음 phase는 M1-7 검증이다. M1-5만 완료했다고 전체 M1 완료로 처리하지 않으며, 후속 단계의 완료 기준 전에 누락된 M1-6과 모듈 테스트를 별도로 완료해야 한다. 이 문서 작성으로 상위 phase 목록이나 notification 구현을 앞당기지 않는다.
