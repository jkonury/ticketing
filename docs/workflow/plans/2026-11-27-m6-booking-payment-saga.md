---
status: done
approved_at: 2026-11-28T06:08:54+09:00
---

# M6 예매–결제 Saga 재검증 실행 계획

## 목표와 근거

M6 사전 작업(P1~P6), Kafka 도입, payment 분리, HTTP 계약 생성, settlement 분리와 P2 배치 기준선 검증이 완료됐다. 이번 항목의 완료 기준은 분리 환경에서 기존 회귀 검증이 통과하고, Kafka 브로커와 payment 앱을 각각 중단한 뒤에도 해당 주문의 환불·좌석·쿠폰 상태가 기대한 결과로 수렴하는 것이다. [M6 로드맵](../../roadmap.md#m6-msa--k8s), [M6 단계별 완료 기준](../../plans/m6-msa.md#3-본-작업-순서와-완료-기준), [정산 분리 실행 계획](2026-11-27-m6-settlement-split.md)

- 결제·환불 이벤트는 기존 Kafka 토픽과 주문 키를 사용한다. PG 호출은 DB 트랜잭션 밖에서 하고, 재시도에는 기존 멱등 키를 쓴다. 외부화 실패는 발행 앱의 미완료 발행을 재기동으로 복구하고, 소비 처리 실패는 재시도·DLT 절차를 따른다. [ADR 0011](../../adr/0011-event-driven-payment.md), [ADR 0018](../../adr/0018-payment-retry-boundaries.md), [ADR 0039](../../adr/0039-kafka-externalizes-modulith-events-only.md), [ADR 0043](../../adr/0043-kafka-externalization-step2-decisions.md)
- 쿠폰 보상은 core의 로컬 이벤트로 처리한다. 확정 전 결제 실패·만료·시스템 취소는 해당 예약 시도를 해제하고, 확정은 `USED`로 전환한다. 확정 후 환불에도 `USED`를 유지한다. 좌석 확정은 DB 유니크 제약으로 보호한다. [결제와 쿠폰 흐름](../../architecture/flows.md#64-결제-이벤트-기반), [ADR 0035](../../adr/0035-coupon-module-reservation-saga.md), [ADR 0045](../../adr/0045-coupon-reservation-attempts.md)
- M6 계획의 13개는 작성 당시의 기준선이다. 현재 저장소의 core 통합 테스트 7개와 분리 스택 시나리오 12개(P2 배치 3개 포함)를 모두 유지하고 현재 실행 경계에서 확인한다. [백엔드 테스트 지침](../../rules/backend-testing.md#e2e부하ci-실행)
  - [BookingFlowE2ETests](../../../core/src/test/kotlin/io/github/ticketing/e2e/BookingFlowE2ETests.kt) 1개 — 공연 등록부터 두 좌석 결제 확정까지.
  - [SeatHoldConcurrencyTests](../../../core/src/test/kotlin/io/github/ticketing/e2e/SeatHoldConcurrencyTests.kt) 3개 — 동일 좌석 선점 경쟁, 겹치는 다중 좌석의 원자적 선점, Redis 선점 우회 시 DB 유니크 제약.
  - [PaymentFailurePathTests](../../../core/src/test/kotlin/io/github/ticketing/e2e/PaymentFailurePathTests.kt) 3개 — 결제 실패와 좌석 반환, 중복 승인 이벤트, 만료 후 환불 요청.

## 범위

- 결제 실패·중복 웹훅·만료 후 승인·좌석 충돌·금액 불일치·확정 후 환불의 기존 검증을 재사용한다. 분리 스택에서 쿠폰이 붙은 결제 실패와 두 가지 중단·복구 경로를 추가한다. 기존 설계에 어긋나는 동작을 재현하면 해당 검증과 함께 교정한다.
- 범위 밖은 쿠폰 분리, 새 Saga 오케스트레이터나 자동 재처리 정책, 업무 HTTP·Kafka 계약 변경, 실제 PG, 부하 측정, 컨테이너 이미지와 k8s다.

## task

### T1. 실패 웹훅이 예매를 취소하고 예약 쿠폰을 복원한다

- [x] 완료
- Blocked by: 없음
- 변경 범위와 제약: 기존 fake-pg 승인 보류 테스트 제어에 보류 중인 주문을 실패로 끝내는 동작을 추가한다. 기존 실패 웹훅 발송 경로를 사용한다. 제어는 기존 `fake-pg.test-controls.enabled=true`에서만 가능하며, 같은 주문의 보류 거래는 한 번만 마친다. `CouponFlowE2eTests`의 쿠폰 예매 준비를 재사용하고 후속 복구 테스트에 필요한 주문·회차·쿠폰 식별자를 공유한다.
- 테스트 경계와 선례: fake-pg HTTP 테스트는 `ApprovalHoldTest`, `ApprovalHoldDisabledTest`, `PaymentScenarioTest`를 따른다. 보류된 거래에서 `PAYMENT_FAILED` 웹훅이 한 번 발행되고, 제어가 꺼져 있거나 이미 처리한 주문은 실패를 다시 발송하지 않는다. 분리 스택에서는 Gateway 예매·결제 요청 → fake-pg 실패 웹훅 → payment → Kafka → core를 거쳐 결제 `FAILED`, 예매 `CANCELLED`와 사유 `PAYMENT_FAILED`, 같은 쿠폰 `ISSUED`, 판매 좌석 미생성 및 다른 구매자의 재선점을 확인한다.
- AC — fake-pg 테스트 통과 및 E2E 테스트 소스 컴파일·정적 검사 통과. 시나리오 실행은 최종 CI에서 확인한다.

  ```bash
  ./scripts/agent/test/run-tests.sh :fake-pg:test --tests '*ApprovalHold*' --tests '*PaymentScenarioTest'
  ./scripts/agent/test/check-summary.sh :e2e:compileTestKotlin :e2e:ktlintCheck :e2e:detekt
  ```

### T2. Kafka 브로커가 복구되면 만료된 주문의 환불과 쿠폰 해제가 완료된다

- [x] 완료
- Blocked by: T1(쿠폰 예매 준비와 PG 보류 제어 재사용)
- 변경 범위와 제약
  - `scripts/e2e/run.sh`에 장애 시나리오용 `--saga-recovery`를 추가하고 `e2e`의 Gradle 테스트 설정에서 `SagaRecoveryE2eTests`를 이 옵션에 연결한다. 현재 기본 실행은 개발용 Compose를 공유할 수 있으므로 일반 실행에서는 이 클래스가 제외된다. 옵션을 사용하려면 호출 환경에 기본 프로젝트와 다른 전용 `COMPOSE_PROJECT_NAME`을 명시해야 한다. 이름이 없거나 기본 프로젝트와 같으면 스크립트가 빌드·Compose 기동 전에 종료 코드 2로 거부한다. `--web`과 장애 옵션을 함께 지정해도 같은 방식으로 거부한다. 로컬은 런북 §6에 따라 별도 작업 디렉터리와 `.env`, 고유 프로젝트·브리지 이름, 별도 인프라·앱 포트를 준비한다. `backend E2E` CI는 격리된 러너에서 실행 ID와 시도 번호를 붙인 전용 프로젝트를 지정하고 `--saga-recovery`를 전달한다. 실행기가 로컬 프로젝트나 포트를 자동 할당하는 작업은 범위 밖이다. [로컬 격리 절차](../../runbook.md#6-테스트검증-실패-대응), [CI 워크플로](../../../.github/workflows/ci.yml)
  - 테스트 제어는 지정한 Compose 프로젝트의 `kafka` 서비스에 `docker compose pause kafka`와 `docker compose unpause kafka`를 적용한다. 시작 시 대상의 프로젝트·서비스 라벨과 앱이 연결할 브로커 주소를 확인한다. 이미 일시 중지된 브로커에서는 장애 주입을 시작하지 않는다. 일시 중지 직전에 실행별 상태 파일에 컨테이너 ID를 기록하고 테스트의 `finally`와 실행기의 `trap`에서 같은 대상이 여전히 일시 중지 상태면 재개한다. `--keep`에서도 브로커는 재개한다.
  - `BatchSupport` 선례에 따라 실행기가 payment JAR·포트·로그 디렉터리·실행별 PID 파일을 `-De2e.*`로 넘기고 초기 PID를 기록한다. 테스트 JVM은 파일에 기록된 실행기 소유 payment 프로세스를 확인하고 종료를 기다린 뒤, 같은 작업 디렉터리·기동 인자·환경으로 JAR를 시작해 새 PID를 원자적으로 기록한다. PID와 프로세스 시작 정보를 대조해 재사용된 PID를 건드리지 않는다. 중단 이후 시나리오가 실패한 경우에도 테스트 `finally`에서 뒤따르는 테스트를 위해 payment 복구를 시도한다. 셸의 정리와 `--keep` 출력은 현재 PID 파일을 읽고, 정리 시 셸 자식의 회수는 기존대로 처리한다. 최초 `payment.log`는 보존하고 재기동마다 별도 로그를 쓴다.
  - 최초 기동과 재기동 모두 장애 옵션이 켜진 E2E payment에만 `KafkaRecoveryIntegrationTests`의 producer 설정을 적용한다. `max.block.ms=3000`, `delivery.timeout.ms=5000`, `request.timeout.ms=3000`, `linger.ms=0`을 사용하고 웹훅 승인 해제 후 외부화 `FAILED`를 최대 30초 폴링한다. 시나리오 전체 제한은 이 대기와 프로세스 종료·기동, 보상 관찰에 필요한 단계별 상한을 포함한다. 같은 장애 실행에 포함된 일반 테스트도 이 payment 설정을 공유하므로 CI에서 전체 스위트를 확인한다.
- 테스트 경계와 선례: `PaymentFlowE2eTests`처럼 쿠폰 결제의 PG 승인을 보류하고 주문의 만료 시각을 과거로 커밋한다. 브로커를 일시 중지한 뒤 승인을 풀어 payment의 실제 웹훅으로 `SUCCEEDED`가 커밋되고 `PaymentSucceeded` 외부화가 `FAILED`로 남는 것을 같은 주문으로 확인한다. 이때 예매는 `PENDING`, 쿠폰은 `RESERVED`다. 브로커를 재개하고 Kafka 요청 성공을 확인한 뒤 같은 DB와 Kafka로 payment를 재기동한다. readiness는 payment HTTP 기동 확인에 사용한다. 소비 재개는 같은 발행 ID의 완료, 예매 `EXPIRED`, payment와 PG의 `REFUNDED`, 쿠폰 `ISSUED`, 판매 좌석 미생성과 같은 좌석 재선점이 관찰될 때까지 폴링해 확인한다.
- DLT 판정: T2·T3 모두 장애 주입 전에 해당 계약의 DLT 파티션별 끝 오프셋을 기록한다. 최종 상태와 관련 원본 레코드까지의 소비 그룹 오프셋 진행을 확인한 뒤, 기준점부터 새 끝 오프셋 직전까지 이 주문 ID를 키로 가진 DLT 레코드가 없는지 확인한다. 이 주문의 미완료 발행도 확인한다. [DLT 확인과 재처리](../../runbook.md#37-미완료-이벤트-발행이-쌓인다)
- AC — 기본 프로젝트에서 장애 옵션 거부, Docker 기동 후 기존 브로커 복구 테스트 통과, 스크립트 구문 및 E2E 컴파일·정적 검사 통과. 분리 스택에서의 복구는 최종 CI에서 확인한다.

  ```bash
  ./scripts/agent/test/run-tests.sh :core:test --tests '*KafkaRecoveryIntegrationTests'
  ./scripts/agent/test/run-tests.sh :payment:test --tests '*KafkaRecoveryIntegrationTests'
  bash -n scripts/e2e/run.sh
  bash -c 'env -u COMPOSE_PROJECT_NAME ./scripts/e2e/run.sh --saga-recovery; [[ $? -eq 2 ]]'
  ./scripts/agent/test/check-summary.sh :e2e:compileTestKotlin :e2e:ktlintCheck :e2e:detekt
  ```

### T3. payment 앱이 돌아오면 확정 후 취소의 환불이 완료된다

- [x] 완료
- Blocked by: T2(payment 프로세스 수명 관리와 복구 테스트 지원 재사용)
- 변경 범위와 제약: T2의 장애 옵션, payment 생명주기 관리, DLT 확인 절차를 재사용한다. Kafka와 fake-pg는 계속 실행한다.
- 테스트 경계와 선례: `CouponFlowE2eTests`의 할인 결제·확정 후 취소와 payment의 `PaymentListenerTests`의 환불 멱등 검증을 따른다. 예매 `CONFIRMED`, 쿠폰 `USED`, 할인 결제 금액을 확인한 뒤 payment를 내린다. Gateway로 취소하고 판매 좌석 삭제와 재선점, core의 `RefundRequested` 발행 완료, 아직 환불 전인 PG 거래를 확인한다. payment를 같은 그룹으로 재기동해 적재된 환불 요청을 소비하게 하고 결제 조회와 PG의 `REFUNDED`, 같은 거래 한 건, 쿠폰 `USED` 유지를 확인한다. 정산 환불 사본 반영을 폴링하고 T2의 DLT 기준을 적용한다.
- AC — Docker 기동 후 payment 리스너 테스트 통과, 스크립트 구문 및 E2E 컴파일·정적 검사 통과. 앱 중단 중 적재된 환불의 처리는 최종 CI에서 확인한다.

  ```bash
  ./scripts/agent/test/run-tests.sh :payment:test --tests '*PaymentListenerTests'
  bash -n scripts/e2e/run.sh
  ./scripts/agent/test/check-summary.sh :e2e:compileTestKotlin :e2e:ktlintCheck :e2e:detekt
  ```

### T4. 보상 결과와 복구 절차를 문서로 확인할 수 있다

- [x] 완료 ([검증 결과](#검증-결과))
- Blocked by: T1, T2, T3
- 변경 범위와 제약: 흐름 문서의 자동 환불 조건에 금액 불일치와 취소 후 늦은 승인을 반영하고, 결제 재호출 설명을 ADR 0044와 맞춘다. 런북에는 발행 앱별 DB에서 미완료 발행을 찾고 브로커 복구 후 해당 앱을 재기동하는 절차, 이미 Kafka에 적재된 환불의 소비 재개, DLT 확인이 필요한 조건을 주문 상태와 함께 적는다. E2E 문서에 `--saga-recovery`와 격리 사전 조건을, fake-pg 테스트 제어 문서에 T1 동작을 반영한다. [ADR 0044](../../adr/0044-sales-close-and-payment-retry.md), [장애 진단 런북](../../runbook.md#37-미완료-이벤트-발행이-쌓인다), [fake-pg 연동 규약](../../architecture/integrations.md#승인-보류-테스트-제어)
- 테스트 경계와 선례: `BookingPaymentFlowTests`·`BookingCouponSagaTests`로 좌석 충돌, 금액 불일치, 쿠폰 해제·사용 확정 및 이전 시도 재전달을 확인한다. `ExpirePendingBookingsJobTests`·`ReleaseStaleCouponReservationsJobTests`와 P2 기준선으로 배치 보정 경계를 확인한다. 이 테스트와 분리 스택 결과를 근거로 복구 절차를 검토한 뒤 로드맵 Saga 항목을 완료 처리한다.
- AC — Docker 기동 후 관련 테스트와 문서 검사 통과, 최종 CI에서 기존 기준선과 신규 시나리오 통과를 확인한다.

  ```bash
  ./scripts/agent/test/run-tests.sh :core:test --tests '*BookingFlowE2ETests' --tests '*SeatHoldConcurrencyTests' --tests '*PaymentFailurePathTests'
  ./scripts/agent/test/run-tests.sh :core:test --tests '*BookingPaymentFlowTests' --tests '*BookingCouponSagaTests' --tests '*ExpirePendingBookingsJobTests' --tests '*ReleaseStaleCouponReservationsJobTests'
  ./scripts/agent/docs/check-docs.sh
  ```

## 최종 검증

- 시작 SHA부터 HEAD까지 `./scripts/agent/test/verify-changed.sh <시작-SHA>..HEAD`를 실행한다. 필터 없는 `./scripts/agent/test/run-tests.sh`와 `./scripts/agent/test/check-summary.sh ktlintCheck detekt`를 실행한다. 모듈 의존 변경이 발생하면 `ModularityTests`와 `./gradlew :core:modulithDocs`를 적용한다.
- PR의 `backend E2E`는 T2에서 지정하는 전용 Compose 프로젝트로 `./scripts/e2e/run.sh --saga-recovery`를 실행한다. 결과에서 기존 분리 스택 12개와 T1~T3의 신규 3개가 모두 실행됐는지 확인한다. P2의 정산·결제 대사·미결제 정리 결과를 포함한다. 로컬 E2E는 사용자가 명시적으로 실행을 요청한 경우에만 수행하며, 장애 시나리오는 T2의 로컬 격리 조건을 준비한 뒤 실행한다. CI 확인이 남으면 로드맵 항목과 계획 완료 상태를 보류한다. [E2E·부하·CI 실행 지침](../../rules/backend-testing.md#e2e부하ci-실행)

### 검증 결과

- PR #9가 `main`에 병합됐다. 최종 커밋 `80f69e1`의 백엔드 빌드(테스트·ktlint·detekt 포함), 백엔드 E2E, 웹 검사, 문서 검사와 Web E2E가 모두 통과했다. [PR #9](https://github.com/jkonury/ticketing/pull/9) · [CI](https://github.com/jkonury/ticketing/actions/runs/46072018682) · [Web E2E](https://github.com/jkonury/ticketing/actions/runs/46072018722)
- 백엔드 E2E JUnit 리포트에서 P2 배치 3개를 포함한 기존 12개와 신규 3개가 모두 통과했다. 신규 시나리오는 쿠폰 예매의 결제 실패 보상, Kafka 복구 후 만료 예매 환불과 쿠폰·좌석 반환, payment 재기동 후 확정 예매 취소 환불과 쿠폰 사용 상태 유지다. 실패·오류·건너뜀은 0건이었다. [백엔드 E2E 실행](https://github.com/jkonury/ticketing/actions/runs/46072018682/job/140750965443)
- 로컬에서 `d1fc1ab..0ee974e` 변경 범위 검사, 필터 없는 Gradle 테스트, ktlint·detekt 및 완료 문서 검사가 통과했다. Gradle 테스트는 기존 결과를 재사용했다.
