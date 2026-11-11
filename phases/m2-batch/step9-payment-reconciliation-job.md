# Step 9: payment-reconciliation-job

- model: 기본 / reasoning_effort: high

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `core/src/main/kotlin/io/github/ticketing/payment/application/port/required/PgPort.kt`
- `core/src/main/kotlin/io/github/ticketing/payment/adapter/required/pg/FakePgAdapter.kt`, `PaymentPgConfiguration.kt`
- `core/src/test/kotlin/io/github/ticketing/payment/adapter/required/pg/FakePgAdapterTests.kt` (HTTP 어댑터 테스트 형태)
- `core/src/main/resources/db/migration/V6__create_payment_tables.sql` (`payment.payment` 컬럼)
- `core/src/main/kotlin/io/github/ticketing/payment/domain/Payment.kt` (`PaymentStatus`)
- `core/src/main/kotlin/io/github/ticketing/KeysetItemReader.kt`, `core/src/test/kotlin/io/github/ticketing/BatchIntegrationTestSupport.kt` (step 1)
- `docs/architecture.md § 10. 외부 연동 > fake-pg 연동 계약`, `§ 9. Batch`
- `docs/rules/backend.md § Batch (core-batch)`, `§ 헥사고날`, `§ 영속성`, `§ 테스트 > Spring 컨텍스트 재사용`

## 작업

fake-pg 거래 내역과 `payment.payment`를 KST 일자 기준으로 비교해 불일치를 **기록만** 하는 Job을 payment 모듈에 만든다. 자동 보정은 하지 않는다.

### PG 거래 조회 계약 (fake-pg, step 2에서 KST 기준으로 바뀜)

```
GET /v1/transactions?date=YYYY-MM-DD      // Asia/Seoul 기준 요청 날짜
200 [ { transactionId: string, orderId: string(=예매 ID), amount: long(원),
        status: PENDING|APPROVED|FAILED|REFUNDED,
        requestedAt: Instant, approvedAt: Instant?, refundedAt: Instant? } ]
```

### 1. PG 조회 포트 (기존 `PgPort`와 분리)

```kotlin
// application.port.required
interface PgTransactionQueryPort {
    fun findTransactions(date: LocalDate): List<PgTransaction>   // 실패 시 예외
}
data class PgTransaction(val transactionId: String, val orderId: Long, val amount: BigDecimal,
                         val status: PgTransactionStatus, val requestedAt: Instant, ...)
```

- `PgPort`에 메서드를 추가하지 않는다. 이유: 결제 경로 테스트들의 `PgPort` 가짜 구현 3곳이 모두 깨지고, 조회는 결제 요청과 책임이 다르다.
- 구현은 `adapter.required.pg`에 둔다(기존 `RestClient` 빈 재사용). `orderId`를 `Long`으로 바꾸지 못하면 예외로 Step을 실패시킨다.

### 2. 마이그레이션 `V10__create_payment_reconciliation_tables.sql` (payment 스키마)

```
payment.pg_transaction_snapshot           -- 대상 날짜의 PG 거래 사본
  target_date date, transaction_id varchar   -- PK (target_date, transaction_id)
  order_id bigint, amount numeric, status varchar,
  requested_at timestamptz, approved_at timestamptz, refunded_at timestamptz, fetched_at timestamptz

payment.reconciliation_mismatch
  target_date date, order_id bigint, mismatch_type varchar   -- PK (target_date, order_id, mismatch_type)
  pg_transaction_id varchar, pg_status varchar, pg_amount numeric,
  internal_payment_id bigint, internal_status varchar, internal_amount numeric,
  detected_at timestamptz
```

### 3. 비교 규칙 (도메인, 순수 Kotlin)

```kotlin
// payment.domain
enum class MismatchType { MISSING_INTERNAL, MISSING_PG, STATUS_MISMATCH, AMOUNT_MISMATCH, TRANSACTION_ID_MISMATCH }
object ReconciliationRule {
    fun compare(internal: InternalPaymentRecord?, pg: PgTransactionRecord?): List<MismatchType>
}
```

- 매칭 키는 `orderId`. 내부 대상은 `requested_at`이 대상 날짜(KST)인 결제, PG 대상은 같은 날짜의 스냅샷.
- PG만 있음 → `MISSING_INTERNAL`. 내부만 있음 → `MISSING_PG`.
- 상태 대응(이 쌍이 아니면 `STATUS_MISMATCH`): `REQUESTED↔PENDING`, `SUCCEEDED↔APPROVED`, `REFUND_REQUESTED↔APPROVED`, `REFUNDED↔REFUNDED`, `FAILED↔FAILED`.
- 금액은 `compareTo`로 비교, 다르면 `AMOUNT_MISMATCH`.
- 내부 `pg_transaction_id`가 있는데 PG 거래 ID와 다르면 `TRANSACTION_ID_MISMATCH`. 내부 값이 비어 있으면(PG 응답 미확정) 이 검사는 하지 않는다.
- 하나의 주문에 여러 유형이 동시에 나올 수 있다.

### 4. Job: `payment.adapter.provided.batch.PaymentReconciliationJobConfig` (`@Profile("batch")`)

Job 이름 `paymentReconciliationJob`, 파라미터 `targetDate`(필수, 식별). 재실행 정책은 step 5에서 정한 방식과 같다.

1. `fetchPgTransactionsStep` (tasklet): PG 조회 → 그 날짜의 스냅샷 삭제 후 전부 삽입(한 트랜잭션). PG 호출 실패면 Step 실패(재시작 대상).
2. `clearMismatchesStep` (tasklet): 그 날짜의 불일치 행 삭제.
3. `detectMismatchesStep` (chunk): reader는 `order_id` 오름차순 keyset으로 내부 결제(해당 날짜)와 스냅샷(해당 날짜)을 `FULL OUTER JOIN`한 행을 읽는다(payment 스키마 안의 조인이라 허용). processor는 `ReconciliationRule`, writer는 불일치 insert(`ON CONFLICT DO NOTHING`).

- 삭제를 chunk Step 안이 아니라 앞 Step에 두는 이유: chunk Step이 중간에 실패해 재시작되면 이미 기록한 chunk를 지우지 않고 이어서 쓴다. 재실행(새 실행)이면 앞 Step부터 다시 돈다. 이 이유를 Job 설정 KDoc에 남긴다.
- 유형별 건수를 로그로 남긴다.

### 5. 테스트

- 도메인: `core/src/test/kotlin/io/github/ticketing/payment/domain/ReconciliationRuleTests.kt` — 상태 대응표 전부, 금액, 거래 ID, 한쪽만 있음, 복수 유형.
- 어댑터: `FakePgAdapterTests`와 같은 방식으로 거래 조회 응답 매핑·오류 처리 테스트.
- Job: `core/src/test/kotlin/io/github/ticketing/payment/adapter/provided/batch/PaymentReconciliationJobTests.kt` (`BatchIntegrationTestSupport` 상속). `PgTransactionQueryPort` 테스트 대체 구현은 **`BatchIntegrationTestSupport`의 공통 설정에 추가**해 Batch 테스트가 한 컨텍스트를 공유하게 한다.
  - 일치하는 결제는 기록 없음, 유형별 불일치 기록
  - **같은 `targetDate`로 두 번 실행해도 불일치 행이 같다**
  - PG 데이터가 바뀐 뒤 재실행하면 그 날짜의 이전 불일치가 새 결과로 바뀐다
  - PG 조회 실패 시 Job 실패, 고친 뒤 재시작하면 완료

## Acceptance Criteria

```bash
test -f core/src/main/resources/db/migration/V10__create_payment_reconciliation_tables.sql
rg -q 'paymentReconciliationJob' core/src/main/kotlin/io/github/ticketing/payment/adapter/provided/batch
rg -q 'interface PgTransactionQueryPort' core/src/main/kotlin/io/github/ticketing/payment/application/port/required
! rg -q 'findTransactions' core/src/main/kotlin/io/github/ticketing/payment/application/port/required/PgPort.kt
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.payment*" --tests "*ModularityTests" --tests "*HexagonalArchitectureTests"
./scripts/agent/test/check-summary.sh :core:ktlintCheck :core:detekt
```

## 검증 절차

1. `ReconciliationRuleTests` → `FakePgAdapterTests` → `PaymentReconciliationJobTests` 순서로 좁혀 실행해 통과시킨 뒤 AC 전체를 실행한다.
2. Docker로 fake-pg가 떠 있으면 `./gradlew :core-batch:bootRun --args='--spring.batch.job.name=paymentReconciliationJob targetDate=<날짜>'`를 실행해 결과를 완료 보고에 적는다(AC는 아님).

## 금지사항

- 대사 결과로 결제 상태를 바꾸거나 이벤트를 발행하지 마라. 이유: 이번 범위는 검출·기록이다. 복구는 웹훅·재제출 경로가 맡는다(ADR 0018).
- `booking`, `settlement` 스키마를 읽지 마라. 이유: 모듈별 스키마 경계. 대사는 payment 스키마 안에서 끝난다.
- PG 호출을 DB 트랜잭션 안에서 하지 마라. 이유: 트랜잭션 안 외부 호출 금지 규칙. 조회 후 저장 트랜잭션을 연다.
- 불일치 삭제를 chunk Step 안(`beforeStep` 등)에서 하지 마라. 이유: 재시작 시 앞서 쓴 chunk 결과가 지워진다.
- 테스트마다 다른 `@MockitoBean`·`@Import`로 Batch 컨텍스트를 새로 만들지 마라. 이유: 컨텍스트 재사용 규칙.
- 기존 테스트를 깨뜨리지 마라.
