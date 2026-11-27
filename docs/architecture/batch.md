# 9. Batch

> [← 아키텍처 개요](../architecture.md) · 범위: §9 Batch Job, 정산·대사 규칙
> 함께 보기: [flows.md](./flows.md), [erd.md](./erd.md)

| Job | 소유 모듈 | 파라미터 |
|---|---|---|
| `dailySettlementJob` | `settlement`(앱, 실행은 `settlement-batch`) | 식별 파라미터 `targetDate` (ISO 날짜), 재실행별 식별 파라미터 `run.id` (Long) |
| `expirePendingBookingsJob` | `booking` | 실행 구분용 식별 파라미터 `run.id` (Long) |
| `reconcileSeatSnapshotJob` | `booking` | 실행 구분용 식별 파라미터 `run.id` (Long) |
| `releaseStaleCouponReservationsJob` | `coupon` | 실행 구분용 식별 파라미터 `run.id` (Long). `reserved_until + reservation-release-grace`(기본 10m) 경과한 RESERVED를 잠근 뒤 기한을 재검사한다. 해당 시도를 기록하고 조건부 UPDATE로 ISSUED에 복원한다 |
| `reconcileCouponStockJob` | `coupon` | 실행 구분용 식별 파라미터 `run.id` (Long). 발급이 시작된 LIMITED 쿠폰의 Redis 재고·회원 Set을 DB 기준으로 보정. `limited-claim.strategy`가 `redis`가 아니면 아무것도 하지 않고 완료 |
| `paymentReconciliationJob` | `payment`(앱, 실행은 `payment-batch`) | 식별 파라미터 `targetDate` (ISO 날짜), 재실행별 식별 파라미터 `run.id` (Long) |

일별 정산은 공연·주최자별 매출 집계와 수수료 계산을 수행하며 jOOQ reader와 chunk 처리를 사용한다. 미결제 예매 정리는 `PENDING` 예매를 `EXPIRED`로 전환하고, 좌석 스냅샷 보정은 DB 기준으로 Redis 상태를 맞춘다. 결제 대사는 가짜 PG 거래 내역과 내부 결제 기록의 불일치를 검출한다.

쿠폰 재고 보정은 받기가 진행 중이어도 안전한 방향만 고친다. 재고는 Redis 값이 `총 수량 − DB 발급 수`보다 클 때만 낮추고 올리지 않는다(DECR 후 INSERT 전 요청이 있을 수 있어 올리면 초과 발급이 된다). 회원 Set은 DB 발급 회원 중 빠진 회원만 SADD하고, Set에만 있는 회원은 지우지 않는다. 키가 없으면 DB 기준으로 구성한다. 한 Lua(`redis/coupon/reconcile.lua`)로 처리한다. 한계: 보상 실패 등으로 Redis 재고가 DB보다 작아진 누수 재고는 복구하지 않는다(올리면 진행 중 요청과 충돌). 이 경우 진행 중인 받기 요청이 없을 때만 키를 지운다. 그러면 다음 받기가 DB 기준으로 다시 만든다. 진행 중 요청이 있을 때 지우면 Redis 차감 후 INSERT 전인 건이 빠진 채 재구성되어 초과 발급될 수 있다.

Batch Job은 데이터를 소유한 모듈의 `adapter.provided.batch`에 두고 `@Profile("batch")`로 활성화한다. Batch는 웹 컨트롤러와 같은 입력 어댑터다. `core-batch`, `payment-batch`, `settlement-batch`는 각 업무 앱의 컨텍스트를 웹 서버 없이 `batch` 프로필로 띄우며 `spring.batch.job.name`으로 Job 하나를 실행한다. 웹 앱에서는 Job 빈을 만들지 않고 자동 실행도 끈다. [ADR 0019](../adr/0019-batch-jobs-in-owning-modules.md)

- 정산은 `./gradlew :settlement-batch:bootRun --args='--spring.batch.job.name=dailySettlementJob targetDate=2026-11-10,java.time.LocalDate run.id=1,java.lang.Long'`으로 실행한다. Batch 프로필은 HTTP와 Kafka 수신을 끈다. 실패한 실행은 같은 `targetDate`와 `run.id`로 재시작한다.
- 결제 대사 실행: `java -jar payment-batch/build/libs/payment-batch.jar --spring.batch.job.name=paymentReconciliationJob targetDate=<Asia/Seoul 날짜>`.
- core DB, `payment_db`, `settlement_db`에 각각 Flyway로 `batch` 스키마와 Spring Batch 메타데이터를 만든다.
- 정산일은 이벤트 시각의 Asia/Seoul 날짜다. 매출은 그날 확정 사본의 정가 합, 환불은 그날 `refundRequired=true`인 취소 중 확정 사본이 있는 예매의 정가 합이다. 수수료는 `(매출 − 환불) × 수수료율`(기본 `0.10`)이며 원 미만은 0 방향으로 절사한다. 지급액은 `매출 − 환불 − 수수료`이고 `(정산일, 공연)` 단위로 upsert한다. 실행 전에 발행·소비 지연과 DLT를 확인한다. 지연 이벤트가 영향을 준 날짜는 새 `run.id`로 재계산한다. [정산 런북](../runbook.md#10-정산-db-이전-core-db-settlement-스키마--settlement_db)
- 결제를 요청하지 않은 `PENDING` 예매는 `expiresAt` 경과 시, 결제를 요청한 `PENDING` 예매는 `expiresAt + 유예`(설정값, 기본 30분) 경과 시 `EXPIRED`로 전환한다. 이후 도착한 결제 성공은 기존 자동 환불 경로로 처리한다.
- 좌석 스냅샷 보정은 시작 전 회차를 대상으로 `sold_seat`와 선점 키를 기준으로 스냅샷을 다시 쓴다. 첫 보정 뒤 판매 집합을 다시 조회해 새 판매와 판매 취소 양쪽을 보정하며, 취소된 좌석에 살아 있는 선점이 있으면 HELD로 복원한다.
- 결제 대사는 Asia/Seoul 날짜 기준으로 받은 PG 거래와 같은 날 요청된 내부 결제를 `orderId`로 비교하고 불일치를 기록한다. 자동 보정은 하지 않는다.
- 같은 대상 날짜로 재실행해도 결과가 같도록 upsert 또는 날짜 단위 삭제 후 재적재로 쓴다.
