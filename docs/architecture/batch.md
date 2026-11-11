# 9. Batch

> [← 아키텍처 개요](../architecture.md) · 범위: §9 Batch Job, 정산·대사 규칙
> 함께 보기: [flows.md](./flows.md), [erd.md](./erd.md)

| Job | 소유 모듈 | 파라미터 |
|---|---|---|
| `dailySettlementJob` | `settlement` | 식별 파라미터 `targetDate` (ISO 날짜), 재실행별 식별 파라미터 `run.id` (Long) |
| `expirePendingBookingsJob` | `booking` | 실행 구분용 식별 파라미터 `run.id` (Long) |
| `reconcileSeatSnapshotJob` | `booking` | 실행 구분용 식별 파라미터 `run.id` (Long) |
| `paymentReconciliationJob` | `payment` | 식별 파라미터 `targetDate` (ISO 날짜), 재실행별 식별 파라미터 `run.id` (Long) |

일별 정산은 공연·주최자별 매출 집계와 수수료 계산을 수행하며 jOOQ reader와 chunk 처리를 사용한다. 미결제 예매 정리는 `PENDING` 예매를 `EXPIRED`로 전환하고, 좌석 스냅샷 보정은 DB 기준으로 Redis 상태를 맞춘다. 결제 대사는 가짜 PG 거래 내역과 내부 결제 기록의 불일치를 검출한다.

Batch Job은 데이터를 소유한 모듈의 `adapter.provided.batch`에 두고 `@Profile("batch")`로 활성화한다. Batch는 웹 컨트롤러와 같은 입력 어댑터다. `core-batch`는 웹 서버 없이 `batch` 프로필로 core 컨텍스트를 띄우며 `spring.batch.job.name`으로 Job 하나를 실행한다. 웹 앱에서는 Job 빈을 만들지 않고 자동 실행도 끈다. 이 결정의 대안과 근거는 [ADR 0019](../adr/0019-batch-jobs-in-owning-modules.md)를 참고한다.

- 실행: `./gradlew :core-batch:bootRun --args='--spring.batch.job.name=dailySettlementJob targetDate=2026-11-10,java.time.LocalDate run.id=1,java.lang.Long'`. 완료된 같은 날짜를 다시 계산할 때는 식별 파라미터 `run.id`를 새 값으로 바꾼다. 실패한 실행은 같은 `targetDate`와 `run.id`로 재시작한다.
- Batch 메타데이터는 Spring Batch 공식 PostgreSQL DDL을 Flyway 마이그레이션으로 `batch` 스키마에 만든다.
- 정산일은 이벤트 시각의 Asia/Seoul 날짜다. 그날 매출은 `BookingConfirmed` 금액 합, 환불은 `refundRequired=true`인 `BookingCancelled` 중 확정 사본이 있는 예매의 금액 합이다. 지난 날짜의 정산은 다시 계산하지 않는다. 수수료는 `(매출 − 환불) × 수수료율`(설정값, 기본 `0.10`)이며 원 미만은 0 방향으로 절사한다. 지급액은 `매출 − 환불 − 수수료`이고 `(정산일, 공연)` 단위로 upsert한다.
- 결제를 요청하지 않은 `PENDING` 예매는 `expiresAt` 경과 시, 결제를 요청한 `PENDING` 예매는 `expiresAt + 유예`(설정값, 기본 30분) 경과 시 `EXPIRED`로 전환한다. 이후 도착한 결제 성공은 기존 자동 환불 경로로 처리한다.
- 좌석 스냅샷 보정은 시작 전 회차를 대상으로 `sold_seat`와 선점 키를 기준으로 스냅샷을 다시 쓴다.
- 결제 대사는 Asia/Seoul 날짜 기준으로 받은 PG 거래와 같은 날 요청된 내부 결제를 `orderId`로 비교하고 불일치를 기록한다. 자동 보정은 하지 않는다.
- 같은 대상 날짜로 재실행해도 결과가 같도록 upsert 또는 날짜 단위 삭제 후 재적재로 쓴다.
