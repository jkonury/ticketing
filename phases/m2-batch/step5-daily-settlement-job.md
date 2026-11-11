# Step 5: daily-settlement-job

- model: 기본 / reasoning_effort: high

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `core/src/main/resources/db/migration/V8__create_settlement_copy_tables.sql` (step 4)
- `core/src/main/kotlin/io/github/ticketing/settlement/` 전체 (step 4에서 만든 포트·어댑터)
- `core/src/main/kotlin/io/github/ticketing/KeysetItemReader.kt` (step 1)
- `core/src/test/kotlin/io/github/ticketing/BatchIntegrationTestSupport.kt` (step 1)
- `core/src/main/resources/application.yml`, `application-batch.yml` (step 1)
- `docs/architecture.md § 9. Batch` (정산 규칙·실행 방법, step 0·1에서 갱신됨)
- `docs/rules/backend.md § Batch (core-batch)`, `§ 헥사고날`, `§ 영속성`, `§ 테스트`

## 작업

settlement 사본으로 **일별 정산 Job**을 만든다. Spring Batch 6 API(`JobBuilder`, `StepBuilder`, chunk 설정, 테스트 유틸)는 공식 문서로 확인하라.

### 정산 규칙 (합의된 결정)

- 정산일: 이벤트 시각을 `Asia/Seoul` 날짜로 본 값. 대상 구간은 `[targetDate 00:00 KST, 다음 날 00:00 KST)`.
- 공연별로 집계한다. 행 키는 `(settlement_date, performance_id)`, `organizer_id`도 저장한다.
- 매출: 구간 안에 `confirmed_at`이 있는 `booking_sale`의 건수·금액 합.
- 환불: 구간 안에 `cancelled_at`이 있는 `booking_refund` 중 **같은 `booking_id`의 `booking_sale`이 있는 것**의 건수·금액(`booking_sale.amount`) 합. 확정 시각이 다른 날이어도 취소일에 차감한다. 확정 사본이 없는 환불(`SEAT_ALREADY_SOLD` 등 확정 없이 환불된 건)은 제외한다.
- 순매출 = 매출 − 환불 (음수 가능). 수수료 = 순매출 × 수수료율, 원 미만은 `RoundingMode.DOWN`(0 방향) 절사. 지급액 = 순매출 − 수수료.
- 수수료율: `ticketing.settlement.fee-rate` (기본 `0.10`, `@ConfigurationProperties`), 0 이상 1 미만 검증. 적용한 율을 행에 저장한다.
- 지난 날짜의 정산은 다른 날짜의 Job이 고치지 않는다.

### 1. 마이그레이션 `V9__create_daily_settlement.sql`

```
settlement.daily_settlement
  settlement_date date, performance_id bigint       -- PK (settlement_date, performance_id)
  organizer_id bigint NOT NULL                       -- 인덱스 (organizer_id, settlement_date)
  sale_count int, sale_amount numeric
  refund_count int, refund_amount numeric
  net_amount numeric, fee_rate numeric(5,4), fee_amount numeric, payout_amount numeric
  calculated_at timestamptz
```

### 2. 도메인 (순수 Kotlin)

```kotlin
// settlement.domain
data class FeeRate(val value: BigDecimal)   // 0 <= value < 1
data class DailySettlement(...)             // 위 컬럼과 대응
object DailySettlementCalculator {          // 또는 FeeRate의 메서드
    fun settle(date: LocalDate, performanceId: Long, organizerId: Long,
               saleCount: Int, saleAmount: BigDecimal,
               refundCount: Int, refundAmount: BigDecimal, feeRate: FeeRate): DailySettlement
}
```

### 3. 포트·어댑터

```
application.port.required.SettlementQueryPort
    fun findPerformanceTotals(date: LocalDate, afterPerformanceId: Long?, limit: Int): List<PerformanceTotals>
application.port.required.DailySettlementCommandPort
    fun upsertAll(settlements: List<DailySettlement>)
adapter.required.jooq.*      // 집계는 settlement 스키마 안의 GROUP BY·조인, upsert는 ON CONFLICT DO UPDATE
```

- 집계 쿼리는 `performance_id` 오름차순 keyset 페이지다. 매출만 있는 공연, 환불만 있는 공연을 모두 포함한다.

### 4. Job: `settlement.adapter.provided.batch.DailySettlementJobConfig` (`@Profile("batch")`)

- Job 이름 `dailySettlementJob`, Step 하나 `dailySettlementStep`.
- reader: `KeysetItemReader`(키 `performanceId`) + `SettlementQueryPort`. processor: 도메인 계산. writer: `DailySettlementCommandPort.upsertAll`. chunk 크기는 설정값(기본 100).
- Job 파라미터 `targetDate`(`LocalDate`, 필수, 식별). 없으면 시작 전에 실패한다.
- **재실행 정책**: 같은 `targetDate`로 다시 실행할 방법을 하나 정한다(예: `RunIdIncrementer` 같은 증가 파라미터를 함께 쓰거나 비식별 파라미터 추가). 정한 방법과 실행 예시를 `docs/architecture.md § 9. Batch`의 실행 줄에 덧붙인다. 실패한 실행은 같은 파라미터로 재시작하면 남은 chunk부터 이어진다.
- 배치 경로는 `@Transactional`을 `application.service`에만 둔다는 규칙과 충돌하지 않게 한다. chunk 트랜잭션은 Spring Batch가 관리하고, writer는 포트를 호출한다(필요하면 `application.service`의 유스케이스를 거친다).

### 5. 테스트

- `core/src/test/kotlin/io/github/ticketing/settlement/domain/DailySettlementCalculatorTests.kt`: 절사(양수·음수), 환불만 있는 날(음수 지급액), 수수료율 범위 검증.
- `core/src/test/kotlin/io/github/ticketing/settlement/adapter/provided/batch/DailySettlementJobTests.kt` (`BatchIntegrationTestSupport` 상속). 사본은 테스트에서 포트나 jOOQ로 직접 넣는다. 다른 테스트와 겹치지 않는 고유한 공연 ID와 날짜를 쓴다.
  - KST 경계: `2026-11-10T14:59:59Z`는 11/10, `2026-11-10T15:00:00Z`는 11/11 정산
  - 이전 날짜에 확정된 예매가 오늘 환불되면 오늘 차감, 이전 날짜 행은 그대로
  - 확정 사본 없는 환불은 제외
  - **같은 `targetDate`로 두 번 실행해도 행 수와 금액이 같다**
  - **재시작**: chunk 크기 1로 두고 두 번째 chunk 쓰기에서 한 번 실패하게 만든 뒤(테스트 전용 대체 빈 등) 같은 파라미터로 재시작하면 완료되고 모든 공연 행이 정확히 한 번 존재한다
  - `targetDate` 없이 실행하면 실패

## Acceptance Criteria

```bash
test -f core/src/main/resources/db/migration/V9__create_daily_settlement.sql
rg -q 'dailySettlementJob' core/src/main/kotlin/io/github/ticketing/settlement/adapter/provided/batch
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.settlement*" --tests "*ModularityTests" --tests "*HexagonalArchitectureTests"
./scripts/agent/test/check-summary.sh :core:ktlintCheck :core:detekt
```

## 검증 절차

1. `DailySettlementCalculatorTests` → `DailySettlementJobTests` 순서로 좁혀 실행해 통과시킨 뒤 AC 전체를 실행한다.
2. Docker가 떠 있으면 `./gradlew :core-batch:bootRun --args='--spring.batch.job.name=dailySettlementJob targetDate=<날짜>'`를 두 번 실행해 두 번째도 성공하는지 확인하고 완료 보고에 적는다(AC는 아님).

## 금지사항

- `booking`, `payment` 스키마를 읽거나 조인하지 마라. 이유: settlement는 자기 스키마의 사본만 읽는다.
- 대상 날짜의 사본을 한 번에 메모리에 올려 계산하지 마라. 이유: chunk 지향 처리 규칙. 공연 단위 keyset 페이지로 읽는다.
- 정산 결과를 insert만 하지 마라. 이유: 재실행 시 중복된다. `(settlement_date, performance_id)` upsert로 쓴다.
- 금액을 `Double`로 계산하지 마라. 이유: 금액은 `BigDecimal`, 비교는 `compareTo`.
- 현재 시각을 `Instant.now()`로 얻지 마라. 이유: 주입된 `Clock`을 쓴다(`calculated_at`).
- 다른 날짜의 정산 행을 수정·삭제하지 마라. 이유: 합의된 규칙상 지난 정산은 고치지 않는다.
- 기존 테스트를 깨뜨리지 마라.
