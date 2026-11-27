---
status: approved
approved_at: 2026-11-27T17:43:15+09:00
---

# M6 정산 분리 실행 계획

## 목표와 근거

M6 사전 작업과 Kafka 도입·payment 분리·HTTP 계약 생성이 완료됐다. 이번 항목은 정산을 자체 DB에 쓰는 앱과 배치 실행 단위로 분리한다. 분리 후 Batch E2E에서 P2 기준선과 같은 정산 결과가 나오고, 주최자는 기존 Gateway 경로에서 과거와 신규 정산을 조회할 수 있어야 한다. [로드맵](../../roadmap.md#m6-msa--k8s), [M6 설계 계획과 P2 기준선](../../plans/m6-msa.md)

- 예매 확정·취소의 기존 계약 JSON, 이벤트별 토픽과 `bookingId` 키를 사용한다. core의 알림은 같은 JVM의 기존 이벤트를 소비한다. [이벤트 카탈로그](../../architecture/events.md#kafka-외부화-대상-m6), [ADR 0042](../../adr/0042-contract-module-keeps-packages-and-json-compat-rules.md), [ADR 0043](../../adr/0043-kafka-externalization-step2-decisions.md)
- 정가·플랫폼 할인 부담 산식, KST 집계, 주최자 인가와 HTTP 응답을 유지한다. Job 코드는 정산 앱에 두고 `settlement-batch`로 실행한다. [ADR 0019](../../adr/0019-batch-jobs-in-owning-modules.md), [ADR 0036](../../adr/0036-settlement-list-price-platform-discount.md), [배치 설계](../../architecture/batch.md)
- 같은 PostgreSQL 인스턴스에 전용 계정과 `settlement_db`를 두고 점검 창에 전환한다. 판매·환불 사본과 일별 정산 결과를 옮긴다. 기존 배치 실행 이력은 core DB에 보존한다. [ADR 0051](../../adr/0051-settlement-database-cutover.md)

## 범위

- 포함: core Kafka 발행, 정산 앱·배치·전용 DB, 주최자 조회 라우팅과 OpenAPI/BFF, 데이터 이전 도구·런북·격리 환경 리허설, 로컬 실행·E2E·검사 연결, core의 기존 정산 코드 제거.
- 범위 밖: 공유·운영 DB 적용, 전환 뒤 옛 core 정산 스키마의 물리적 삭제, 예매–결제 Saga 재검증 단계, 컨테이너 이미지·k8s, 정산 정책 변경.

## task

### T1. core에서 예매 확정·취소를 Kafka로 발행

- [x] 완료
- Blocked by: 없음
- 변경 범위와 제약: core의 `BookingEventExternalizationConfig`와 common의 `KafkaTopics`에 `ticketing.booking.booking-confirmed`, `ticketing.booking.booking-cancelled`를 연결한다. 토픽당 파티션 3개, 문자열 `bookingId` 키, 타입 헤더 없는 기존 골든 JSON, 기존 DLT 규칙을 적용한다. 전환 전에는 core의 정산 리스너와 알림 리스너를 유지한다.
- 테스트 경계와 선례: core가 게시한 계약 이벤트를 실제 Kafka 레코드에서 확인한다. `PaymentEventExternalizationTests`와 `KafkaRecoveryIntegrationTests`를 선례로 골든 본문·키·헤더를 검증하고, 브로커 중단 중 발생한 정산 이벤트가 미완료 레지스트리에 남았다가 복구 후 발행되는지 확인한다. 기존 직렬화·토픽 선언·Modulith 검사도 실행한다.
- AC — Docker 기동 후 실행한다.

  ```bash
  ./scripts/agent/test/run-tests.sh :contract:test
  ./scripts/agent/test/run-tests.sh :core:test --tests '*BookingSettlementExternalizationTests' --tests '*ContractEventPublicationSerializationTests' --tests '*KafkaInfrastructureTests' --tests '*ModularityTests'
  ```

### T2. 정산 앱이 Kafka에서 사본을 적재

- [x] 완료
- Blocked by: T1
- 변경 범위와 제약: `settlement` Gradle 앱, 전용 계정·DB 준비, Flyway와 독립 jOOQ 생성을 추가한다. 앱은 `common`과 `contract`를 사용하고 core 및 core DB에 연결하지 않는다. 기존 판매·환불 적재 유스케이스를 옮긴다. 두 토픽을 `ticketing-settlement` 그룹에서 소비하고, 빈 그룹의 최초 구독은 보관 중인 레코드부터 읽는다. DB 쓰기가 커밋되기 전에 메시지 처리를 완료하지 않는다. 신규 앱의 강제 기동이나 기존 환경 전환은 T6에서 한다.
- 테스트 경계와 선례: `SettlementModuleTests`의 검증을 새 앱의 Kafka → PostgreSQL 통합 테스트로 옮긴다. 동일한 확정·취소의 중복 수신, 확정보다 먼저 도착한 환불, `refundRequired=false`, 잘못된 본문의 DLT를 실제 Kafka·정산 DB에서 확인한다. 전용 계정으로 기동하고 core DB와 정산 DB의 교차 접속 제한도 확인한다. 로컬 관리자 계정은 DB 준비와 테스트에서 사용한다. 독립 빌드와 코드 생성은 payment 선례를 따른다.
- AC — Docker 기동 후 실행한다.

  ```bash
  ./scripts/agent/test/run-tests.sh :settlement:test --tests '*SettlementKafkaIntegrationTests'
  ./scripts/agent/test/check-summary.sh :settlement:bootJar
  ```

### T3. 독립 배치에서 일별 정산 계산

- [x] 완료
- Blocked by: T2
- 변경 범위와 제약: `dailySettlementJob`과 집계·저장 로직을 정산 앱으로 옮기고 `settlement-batch.jar`를 추가한다. Job 이름과 `targetDate`, `run.id` 계약을 유지한다. `settlement_db`에 배치 메타데이터를 두고 비웹 배치 프로필에서 Kafka 소비를 시작하지 않는다.
- 테스트 경계와 선례: `DailySettlementJobTests`와 `DailySettlementCalculatorTests`를 옮겨 Job 실행과 정산 DB 결과를 검증한다. KST 경계, 다른 날 확정된 환불, 쿠폰 부담, 같은 날짜 재실행 시 행 중복 방지, 실패 청크 재시작, 필수 파라미터를 확인한다. 확정 사본보다 환불 사본이 먼저 적재된 예매도 두 사본이 도착한 뒤 올바르게 집계한다. `payment-batch`를 런처 선례로 쓴다.
- AC — Docker 기동 후 실행한다.

  ```bash
  ./scripts/agent/test/run-tests.sh :settlement:test --tests '*DailySettlementJobTests' --tests '*DailySettlementCalculatorTests'
  ./scripts/agent/test/check-summary.sh :settlement-batch:bootJar
  ```

### T4. 독립 앱의 주최자 정산 조회 공개 경계 준비

- [ ] 완료
- Blocked by: T3
- 변경 범위와 제약: 정산 앱에 `GET /api/v1/settlements`와 전용 OpenAPI 스냅샷을 추가한다. T3의 일별 정산 테이블을 조회한다. 기존 JWT 서명·역할 규칙을 사용하고 JWT의 주최자 ID로 조회 범위를 결정한다. Gateway 라우트 및 BFF의 실제 계약 전환은 T6에서 한다. OpenAPI 수집은 기존 앱 방식으로 한다. [ADR 0047](../../adr/0047-openapi-codegen-for-bff-boundary.md)
- 테스트 경계와 선례: `SettlementApiIntegrationTests`를 정산 앱의 HTTP 경계에 옮긴다. 주최자별 격리·정렬, 인증 없음 401, 권한 없음 403, 잘못된 기간 400, 기존 응답 필드와 플랫폼 할인 부담액의 비노출을 확인한다. `OpenApiContractTests`로 정산 앱의 스펙을 고정한다.
- AC — Docker 기동 후 실행한다.

  ```bash
  ./scripts/agent/test/run-tests.sh :settlement:test --tests '*SettlementApiIntegrationTests' --tests '*OpenApiContractTests'
  ```

### T5. 기존 정산 데이터 이전과 점검 창 리허설

- [ ] 완료
- Blocked by: T4
- 변경 범위와 제약: 전용 DB Flyway 적용 후 세 정산 테이블을 복사하는 `scripts/db` 이전 도구와 점검 창 런북을 추가한다. 점검 창에서는 새 예매 관련 쓰기와 배치 실행을 정지하고 진행 중인 결제 결과 처리·예매 정산 리스너·Kafka 외부화 및 배치 실행을 정리한다. 기존 배치 실행 이력은 core DB에 둔다. 세 정산 테이블의 건수와 PK 순 정규화 체크섬을 같은 스냅샷에서 비교하고, 실패나 불일치 시 대상 적재를 롤백한다. 원본과 대조 없이 이미 채워진 대상에 덮어쓰지 않는다.
- 테스트 경계와 선례: `test-migrate-payment-data.sh` 방식의 격리된 PostgreSQL 리허설로 기존 배치 이력, 정가·할인·환불·과거 정산을 사전 적재한다. 건수·체크섬, 과거 정산 HTTP 조회, 기존 이력 보존, 대상 비어 있지 않음과 적재 실패 롤백을 확인한다. 재실행 시 검증된 대상은 중복 적재하지 않고, 불일치하는 대상은 중단한다.
- 전환 검증: 쓰기를 재개하기 전 두 토픽의 파티션별 종료 오프셋을 기록하고 새 소비자가 해당 지점까지 처리했는지 확인한다. 기존 사본과 겹치는 Kafka 레코드를 재생해도 행과 금액이 유지되어야 한다. 정산 관련 미완료 발행과 미처리 DLT가 있으면 조회·배치 전환을 보류한다. 쓰기 재개 전에는 원본으로 복귀할 수 있도록 절차를 시험한다. 재개 후에는 새 DB의 사본과 정산 결과를 기준으로 복구한다. 런북에는 배치 시작 전 발행·소비 지연 확인과 늦게 도착한 이벤트의 영향 날짜를 `run.id`로 재계산하는 절차를 적는다.
- AC — Docker와 로컬 `.env`를 준비하고 격리 DB로 실행한다. 새 스크립트 이름은 이 task의 산출물이다.

  ```bash
  ./scripts/agent/test/run-summary.sh -- ./scripts/db/test-migrate-settlement-data.sh
  ./scripts/agent/docs/check-docs.sh
  ```

### T6. Gateway·BFF·배치 호출처를 새 정산 앱으로 전환

- [ ] 완료
- Blocked by: T5
- 변경 범위와 제약: Gateway의 기존 경로를 정산 앱으로 우선 라우팅하고 기존 JWT 전달과 rate limit을 유지한다. core의 정산 컨트롤러·로컬 리스너·정산 Job 등록을 제거한다. 새 앱에서 동일 동작을 검증하는 테스트로 교체한 뒤 core의 해당 어댑터 테스트를 정리한다. core와 정산의 OpenAPI 스냅샷 및 생성 대상을 동기화하고 BFF의 정산 응답 스키마 사용처를 옮긴다. 개발·E2E 실행 스크립트, `.env.example`, 검사 매핑에 정산 앱과 배치 실행 단위를 연결한다. 기존 데이터가 있는 환경은 T5 런북으로 전환한다.
- 테스트 경계와 선례: `GatewayRoutingTests`의 payment 라우팅 선례로 인증 헤더·쿼리 전달과 라우팅을 확인한다. `BatchBaselineE2eTests`의 정산 대상만 정산 JAR·DB로 바꾸고, Gateway 경유 주최자 조회도 확인한다. 정산 배치 종료 코드 0·`COMPLETED`, 판매 3건·30,000원, 환불 1건·10,000원, 순액 20,000원, 수수료율 0.10·수수료 2,000원, 정산액 18,000원, 할인 부담 3,000원을 단언한다. 다른 두 Batch E2E는 이전과 같은 대상에서 검증한다.
- AC — Docker·로컬 `.env`·가용 포트를 준비하고 격리 Compose 환경에서 실행한다.

  ```bash
  ./scripts/agent/test/run-tests.sh :gateway:test --tests '*GatewayRoutingTests'
  ./scripts/agent/test/run-tests.sh :core:test --tests '*OpenApiContractTests'
  ./scripts/agent/test/web-check.sh --build
  ./scripts/agent/test/run-summary.sh -C web -- pnpm run api:check
  ./scripts/e2e/run.sh --tests '*BatchBaselineE2eTests'
  ```

### T7. core의 정산 구현 제거와 문서 갱신

- [ ] 완료
- Blocked by: T6
- 변경 범위와 제약: core에 남은 정산 구현과 jOOQ 생성 범위, 이전된 테스트와 Modulith 모듈 목록을 정리한다. 옛 core Flyway 마이그레이션과 정산 스키마를 보존한다. 정산 앱의 헥사고날 구조 검사를 유지하고 Modulith 다이어그램, 아키텍처·데이터 소유·ERD·이벤트·Batch·HTTP 계약 문서, 용어사전과 실행 지침을 갱신한다. M6 정산 완료 체크는 이전 리허설과 P2 기준선 검증 결과를 확인한 후 갱신한다.
- 테스트 경계와 선례: core의 `ModularityTests`가 정산 모듈 없이 통과하고 `notification → booking` 이벤트 의존이 남는지 확인한다. core와 정산 앱의 `HexagonalArchitectureTests`가 각자 소유하는 구조를 검증한다. `BatchBaselineE2eTests`로 분리 후 계산과 조회가 계속 동작하는지 확인한다.
- AC — Docker와 격리 E2E 환경 준비 후 실행한다.

  ```bash
  ./scripts/agent/test/run-tests.sh :core:test --tests '*ModularityTests' --tests '*HexagonalArchitectureTests'
  ./scripts/agent/test/run-tests.sh :settlement:test --tests '*HexagonalArchitectureTests'
  ./gradlew :core:modulithDocs
  ./scripts/agent/docs/check-docs.sh
  ./scripts/e2e/run.sh --tests '*BatchBaselineE2eTests'
  ```

## 최종 검증

- 시작 SHA부터 HEAD까지 `./scripts/agent/test/verify-changed.sh <시작-SHA>..HEAD`를 실행한다. 필터 없는 `./scripts/agent/test/run-tests.sh`와 `./scripts/agent/test/check-summary.sh ktlintCheck detekt`를 실행한다.
- 격리된 Compose 환경에서 `./scripts/e2e/run.sh --web`으로 백엔드·웹 전체 E2E를 실행한다. PR CI에서는 전체 백엔드 E2E 및 web E2E 결과와 OpenAPI 생성물 drift 검사를 확인한다.
- 같은 SHA 범위를 승인 계획을 명세로 삼아 `review-diff`의 Standards·Spec으로 검토한다. P2 정산 결과와 이전 리허설 결과를 계획에 남긴다.
