# ADR 0040: payment는 같은 PostgreSQL 인스턴스의 별도 DB로 분리한다

- 상태: 합의
- 작성일: 2026-11-18
- 대체: 없음
- 대체됨·일부 변경: 없음
- 관련: [ADR 0017](0017-payment-owns-payment-events.md), [ADR 0018](0018-payment-retry-boundaries.md), [ADR 0019](0019-batch-jobs-in-owning-modules.md), [M6 계획](../plans/m6-msa.md)

## 맥락

`payment`를 별도 배포 단위로 분리하면 스키마도 core와 다른 DB로 옮겨야 한다. 지금은 한 인스턴스·한 DB 안에서 스키마로만 나뉘어 있다. 모듈 규칙상 스키마 간 외래 키와 조인은 이미 없다(`payment`는 `orderId`만 안다). 이전 대상은 `payment`, `webhook_event`, `pg_transaction_snapshot`, `reconciliation_mismatch`다.

## 검토한 대안

- (A) 같은 PostgreSQL 인스턴스에 별도 DB(`payment_db`)와 전용 계정을 둔다.
- (B) 별도 인스턴스를 둔다(compose에 postgres 추가, k8s에서는 StatefulSet 추가).
- (C) 스키마만 두고 앱만 분리한다(현행 유지).

| 안 | 장점 | 단점 |
|---|---|---|
| A | compose·k8s 자원 증가가 작다. 스키마 경계가 DB 경계(계정·접속 권한)로 승격된다 | 장애·자원 격리는 없다 |
| B | 실제 MSA에 가장 가깝다 | 로컬 자원과 운영 비용이 늘어 학습 목적에 비해 과하다 |
| C | 가장 쉽다 | 로드맵의 "별도 DB로 이전"과 어긋나고, 앱이 core DB를 계속 공유한다 |

## 결정

**(A)**. payment는 같은 인스턴스의 별도 DB `payment_db`와 전용 계정을 쓴다. core 계정은 `payment_db`에 접근하지 못한다.

데이터 이전 절차(무중단 이중 쓰기는 하지 않는다. 짧은 점검 창을 둔다):

1. `payment_db`를 만들고 payment 앱의 Flyway로 `payment` 스키마와 `event_publication`을 생성한다.
2. 점검 창을 열고 core·payment 쓰기를 멈춘다(미완료 `event_publication` 항목을 먼저 비운다).
3. `pg_dump`로 core DB의 payment 스키마 데이터를 `payment_db`로 옮긴다.
4. 테이블별 건수와 금액·상태 체크섬을 대조한다.
5. payment 앱을 올리고 core의 payment 모듈 제거 빌드로 전환한다.
6. core DB의 payment 스키마는 한 단계 검증 뒤 별도 마이그레이션으로 제거한다.

- payment는 `event_publication`을 자기 DB에 따로 둔다. 레지스트리를 core와 공유하지 않는다.
- jOOQ 코드 생성과 Testcontainers는 payment 앱 모듈이 자기 마이그레이션으로 수행한다.
- 결제 대사 Batch는 [ADR 0019](0019-batch-jobs-in-owning-modules.md)에 따라 payment 쪽으로 따라간다. 실행 구성은 계획서에서 정한다.

## 결과

- 얻는 것: 소유자 하나 원칙의 물리적 강제(계정 분리), 낮은 인프라 비용.
- 감수하는 것: 한 인스턴스 장애는 두 DB에 모두 영향을 준다. 점검 창이 필요하다.
- 바뀌는 문서·지침: 구현 단계에서 `docs/architecture/data-ownership.md`, `erd.md`, `docs/architecture.md` §3·§5를 고친다. 지금은 바꾸지 않는다.

## 변경 이력

- 2026-11-18: 작성(합의)
