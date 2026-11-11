# Phase M2: Batch

- 기준: `docs/roadmap.md § M2. Batch`
- 완료 기준: 세 Job(일별 정산, 미결제 정리, 결제 대사)과 좌석 스냅샷 보정 Job이 로컬에서 실행되고, 같은 날짜로 다시 실행해도 결과가 중복되지 않는다.
- 각 step은 독립 세션에서 순서대로 실행한다. 앞 step의 AC가 통과해야 다음 step을 시작한다.

## 합의된 결정 (2026-11-11)

| 주제 | 결정 |
|---|---|
| Job 위치 | 데이터를 소유한 모듈의 `adapter.provided.batch` 패키지에 Job을 정의하고 `@Profile("batch")`로 켠다. `core-batch`는 core 컨텍스트를 웹 없이 띄우는 런처다 |
| 정산 원천 | `BookingConfirmed`, `BookingCancelled`에 `performanceId`, `organizerId`를 추가한다. settlement는 booking 이벤트만 구독한다 |
| 결제 대사 | `payment` 모듈이 맡는다(architecture.md의 "settlement가 대사" 문구를 고친다) |
| 정산 규칙 | 정산일은 이벤트 시각의 Asia/Seoul 날짜. 그날 매출 = 그날 확정 금액 − 그날 환불 취소 금액. 지난 정산은 고치지 않는다. 수수료율은 설정값(기본 10%), 원 미만은 0 방향 절사. `(정산일, 공연)` 단위 upsert |
| Batch 메타데이터 | 공식 PostgreSQL DDL을 Flyway 마이그레이션으로 `batch` 스키마에 만든다 |
| 미결제 정리 범위 | 결제를 요청하지 않은 건은 `expiresAt` 경과 시, 요청한 건은 `expiresAt + 유예(기본 30분)` 경과 시 `EXPIRED`. 그 뒤 도착한 결제 성공은 기존 자동 환불 경로로 처리된다 |
| 대사 날짜 | fake-pg 거래 조회의 날짜 기준을 Asia/Seoul로 바꾸고 대사도 KST 일자로 한다 |

## Step

| N | 이름 | 대상 | model / reasoning_effort |
|---|---|---|---|
| 0 | [batch-decisions-docs](./step0-batch-decisions-docs.md) | docs | haiku / medium |
| 1 | [batch-infra](./step1-batch-infra.md) | core 빌드·설정, core-batch | 기본 / high |
| 2 | [fake-pg-kst-date](./step2-fake-pg-kst-date.md) | fake-pg | haiku / low |
| 3 | [booking-event-enrichment](./step3-booking-event-enrichment.md) | core `booking` | 기본 / medium |
| 4 | [settlement-ingest](./step4-settlement-ingest.md) | core `settlement` (이벤트 수신) | 기본 / medium |
| 5 | [daily-settlement-job](./step5-daily-settlement-job.md) | core `settlement` (Job) | 기본 / high |
| 6 | [settlement-query-api](./step6-settlement-query-api.md) | core `settlement` (웹) | 기본 / medium |
| 7 | [booking-expire-job](./step7-booking-expire-job.md) | core `booking` (Job) | 기본 / high |
| 8 | [seat-snapshot-reconcile-job](./step8-seat-snapshot-reconcile-job.md) | core `booking` (Redis Job) | 기본 / high |
| 9 | [payment-reconciliation-job](./step9-payment-reconciliation-job.md) | core `payment` (Job) | 기본 / high |
| 10 | [docs-finalize](./step10-docs-finalize.md) | docs | haiku / medium |

## Flyway 버전 배정

step끼리 번호가 겹치지 않도록 미리 정한다. 작업 전 `ls core/src/main/resources/db/migration`으로 마지막 번호를 확인하고, 다르면 다음 빈 번호를 쓴다.

| 버전 | step | 내용 |
|---|---|---|
| V7 | 1 | `batch` 스키마와 Spring Batch 메타데이터 테이블 |
| V8 | 4 | `settlement.booking_sale`, `settlement.booking_refund` |
| V9 | 5 | `settlement.daily_settlement` |
| V10 | 9 | `payment.pg_transaction_snapshot`, `payment.reconciliation_mismatch` |
