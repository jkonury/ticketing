# 데이터 소유 (§5)

> [← 아키텍처 개요](../architecture.md) · 범위: 데이터를 누가 소유하고, 누가 어떻게 읽을 수 있는지
> 함께 보기: [erd.md](./erd.md), [redis.md](./redis.md), [events.md](./events.md)

## 1. 원칙

1. 모든 데이터에는 **소유자가 하나**다. 쓰기는 소유자만 한다.
2. 다른 모듈·앱은 소유자의 테이블이나 키를 직접 읽지 않는다. 필요하면 **ID 참조**, **이벤트로 받은 사본**, **소유자가 제공하는 조회 API** 중 하나를 쓴다.
3. 모듈별 PostgreSQL 스키마를 넘는 **외래 키와 조인을 만들지 않는다**. 값으로만 참조한다.
4. `realtime`은 PostgreSQL에 접근하지 않는다. Redis만 쓴다.
5. 좌석 확정의 최종 기준은 DB다. Redis 선점·스냅샷은 파생 상태다.

## 2. PostgreSQL 스키마 소유

PostgreSQL 인스턴스 하나에 core DB, `payment_db`, `settlement_db`를 둔다. 접근 경계는 앱 계정 기준이며 로컬 Compose의 관리자는 DB를 준비하는 슈퍼유저다.

| 스키마 | 소유 모듈 | 주요 테이블 | 비고 |
|---|---|---|---|
| `member` | member | `member`, `refresh_token` | 회원 이용 상태 원본. refresh 토큰은 SHA-256 해시만 저장 |
| `catalog` | catalog | `venue`, `venue_seat`, `performance`, `performance_price`, `schedule` | 공연·회차·공연 판매 상태 원본 |
| `booking` | booking | `schedule`, `schedule_seat`, `performance_sale`, `booking`, `booking_seat`, `sold_seat` | catalog 이벤트로 회차·좌석 사본과 판매 가능 여부·공연 취소 여부를 적재. `sold_seat`가 좌석 확정의 유니크 제약 |
| `coupon` | coupon | `coupon_definition`, `issued_coupon`, `reservation_attempt`, `use_mismatch` | `issued_coupon`의 `(definition_id, member_id)` 유니크가 중복 발급의 최종 방어선. `booking`은 발급 쿠폰 ID와 예매별 시도 ID를 값으로 보관 |
| `audit` | audit | `admin_action` | 관리자 행위 이벤트 사본. `source_event_id` 유니크로 멱등 |
| `batch` | core-batch | Spring Batch 메타데이터 | 공식 DDL을 Flyway로 생성 |
| `public` | Modulith | `event_publication` | 이벤트 발행 레지스트리 |

**`payment_db`** (소유: payment 앱, 전용 계정 `TICKETING_PAYMENT_POSTGRES_USER`, 마이그레이션은 `payment/src/main/resources/db/migration/`)

| 스키마 | 소유 | 주요 테이블 | 비고 |
|---|---|---|---|
| `payment` | payment | `payment`, `webhook_event`, `pg_transaction_snapshot`, `reconciliation_mismatch` | `payment`는 예매를 모르고 `orderId`만 가짐. 웹훅 `event_id`와 `payment`의 `order_id`·`idempotency_key` 유니크가 멱등의 최종 방어선 |
| `batch` | payment-batch | Spring Batch 메타데이터 | 결제 대사 Job용 |
| `public` | Modulith | `event_publication` | payment 앱 자체 레지스트리. core와 공유하지 않는다 |

분리 전 데이터는 `scripts/db/migrate-payment-data.sh`로 core DB의 `payment` 스키마에서 옮긴다. 이전을 대조하고 결제·환불 동작을 검증한 뒤, 원본 백업을 보존하고 core Flyway V18을 적용한다. V18은 core DB의 옛 결제 테이블 네 개와 `payment` 스키마를 제거한다. [runbook](../runbook.md#9-결제-db-이전-core-db-payment-스키마--payment_db), [ADR 0040](../adr/0040-payment-separate-database-same-instance.md)

**`settlement_db`** (소유 계정 `TICKETING_SETTLEMENT_POSTGRES_USER`, Flyway 경로 `settlement/src/main/resources/db/migration/`)

| 스키마 | 소유 | 주요 테이블 | 비고 |
|---|---|---|---|
| `settlement` | settlement 앱·배치 | `booking_sale`, `booking_refund`, `daily_settlement` | 앱은 Kafka에서 판매·환불 사본을 받아 적재하고 배치는 일별 결과를 쓴다 |
| `batch` | settlement-batch | Spring Batch 메타데이터 | 전환 이후 정산 실행 이력 |

점검 창에 core DB의 정산 테이블 세 개를 이전한다. 옛 정산 스키마와 기존 정산 배치 실행 이력은 core DB에 보관한다. [ERD](./erd.md), [정산 이전 런북](../runbook.md#10-정산-db-이전-core-db-settlement-스키마--settlement_db)

## 3. Redis 키 소유

| 용도 | 키 | 쓰기(소유) | 읽기 |
|---|---|---|---|
| 선착순 쿠폰 재고·중복 방지 | `coupon:{<definitionId>}:stock`, `coupon:{<definitionId>}:claimed` | coupon | coupon |
| 불변 좌석 사본 캐시 | `booking:seat-copies:v1:{<scheduleId>}` | booking | booking |
| 좌석 선점 | `booking:hold:{<scheduleId>}:<seatId>` | booking | booking |
| 좌석 스냅샷 | `booking:seats:{<scheduleId>}` | booking | realtime |
| 좌석 변경 스트림 | `booking:seat-events:{<scheduleId>}` | booking | realtime |
| 대기열 | `queue:seq`, `queue:waiting`, `queue:seen`, `queue:active`, `queue:admit-lock` | realtime | realtime |
| 입장 토큰 | `queue:admission:<token>` | realtime | Gateway(검증) |
| 입장 토큰 조회 | `queue:admitted:{<scheduleId>}:<memberId>` | realtime | realtime |
| SSE 티켓 | `sse:ticket:<ticket>` | member(발급) | Gateway(1회 소비) |
| rate limit | Spring Cloud Gateway `RedisRateLimiter` 내부 키 | Gateway | Gateway |
| 조회 캐시 | 공연·회차 정보 | catalog | catalog |
| BFF 세션 | `bff:session:<sessionId>` | web(BFF) | web(BFF) |
| BFF 토큰 갱신 락 | `bff:refresh-lock:<sessionId>` | web(BFF) | web(BFF) |

키 형식과 TTL은 [redis.md](./redis.md)를 본다. 같은 Redis 인스턴스를 공유하지만 키 소유는 위 표처럼 나뉜다. 다른 소유자의 키를 수정하거나 삭제하지 않는다.

## 4. 소유하지 않은 데이터에 접근하는 방법

| 방법 | 쓰는 경우 | 예 |
|---|---|---|
| ID 참조 | 관계를 값으로만 가지면 되는 경우 | `booking.member_id`, `payment.order_id`(=예매 ID) |
| 이벤트로 받은 사본 | 자기 스키마에서 조회·집계해야 하는 경우 | booking의 회차·좌석 사본, settlement의 판매·환불 사본 |
| 소유자의 조회 API | 화면 조합처럼 여러 소유자를 읽어야 하는 경우 | BFF의 `GET /api/v1/payments/me`, `GET /api/v1/admin/payments`, `GET /api/v1/schedules/availability` |
| Gateway 검증 계약 | 요청 경로에서 상태를 확인해야 하는 경우 | 입장 토큰·SSE 티켓은 Gateway가 Redis로 검증 |

### 사본의 규칙

- 사본은 **이벤트로만** 채운다. 소유자 테이블을 직접 읽어 복사하지 않는다.
- 사본의 쓰기는 사본을 가진 모듈의 이벤트 리스너만 한다. 리스너는 멱등이다.
- 원본이 바뀌어도 사본이 소급 갱신되지 않을 수 있다. 사본을 쓰는 로직은 이를 감안한다. 회차 변경 기능을 추가한다면 변경 이벤트를 새로 정의하고 사본 갱신 방식을 정한다.
- booking은 `booking.performance_sale.status_version`보다 큰 공연 판매 상태 변경만 반영한다. 공연 취소 시 `booking.performance_sale`과 `booking.schedule`에 `cancelled=true`, `sale_enabled=false`를 저장한다. 뒤늦게 등록한 회차의 두 값도 공연 사본을 따라 초기화한다.
- 사본의 컬럼은 소비에 필요한 최소한만 둔다.

## 5. 분리 대비 규칙

도메인 소유권을 배포 단위로 옮길 수 있도록 아래 규칙을 지킨다.

- 스키마 간 FK와 조인 금지. 조회가 필요하면 사본이나 API로 해결한다.
- 한 트랜잭션이 두 모듈의 스키마를 함께 쓰지 않는다. 모듈 간 일관성은 이벤트와 보상 흐름으로 맞춘다.
- 모듈이 다른 모듈의 jOOQ 생성 클래스를 import하지 않는다. 구조 검증이 잡는 경계도 같이 본다.

## 6. 변경 시 확인

- [ ] 새 테이블·키의 소유자가 한 곳으로 정해져 있다
- [ ] 다른 소유자의 데이터는 사본, ID 참조, 조회 API 중 하나로만 쓴다
- [ ] 스키마를 넘는 FK·조인이 없다
- [ ] realtime이 PostgreSQL 의존을 가지지 않는다(빌드 의존성으로 확인)
- [ ] 이 문서와 [erd.md](./erd.md) 또는 [redis.md](./redis.md)를 함께 갱신했다
- [ ] 설계가 바뀌면 [ADR](../adr/README.md)을 추가한다
