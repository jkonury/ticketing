# 아키텍처 결정 기록 (ADR)

결정 하나당 파일 하나. 결론은 [architecture.md](../architecture.md)에 있고, 여기에는 **검토한 대안과 선택 이유**를 남긴다.

## 작성 규칙

- 파일명: `NNNN-요약.md` (번호는 순서대로 증가)
- 구성: 상태 / 맥락 / 검토한 대안 / 결정 / 결과
- 이미 수락된 ADR의 결정은 고치지 않는다. 결정이 바뀌면 새 ADR을 쓰고 기존 ADR의 상태를 `대체됨 (NNNN)`으로 바꾼다.
- 새 ADR을 추가하면 아래 목록과 `architecture.md`를 함께 갱신한다.

## 목록

| 번호 | 결정 | 상태 |
|---|---|---|
| [0001](0001-domain-ticketing.md) | 도메인은 지정석 공연 티켓 예매 | 수락 |
| [0002](0002-split-mvc-and-webflux-apps.md) | MVC 코어와 WebFlux 실시간 앱을 배포 단위로 분리 | 수락 |
| [0003](0003-kotlin-everywhere.md) | 백엔드는 전부 Kotlin | 수락 |
| [0004](0004-selective-hexagonal.md) | 헥사고날은 핵심 모듈에만 적용 | 수락 |
| [0005](0005-jpa-write-jooq-read.md) | 쓰기는 JPA, 조회는 jOOQ | 수락 |
| [0006](0006-schema-per-module.md) | 모듈별 스키마 분리, 스키마 간 조인 금지 | 수락 |
| [0007](0007-seat-hold-redis-then-db.md) | 좌석은 Redis 선점 후 DB 확정 | 수락 |
| [0008](0008-queue-in-realtime-app.md) | 대기열은 실시간 앱 소유, 입장 토큰은 Gateway가 검증 | 수락 |
| [0009](0009-realtime-redis-only.md) | 실시간 앱은 Redis만 사용 | 수락 |
| [0010](0010-events-modulith-and-redis-streams.md) | 모듈 간은 Modulith 이벤트, 앱 간은 Redis Streams | 수락 |
| [0011](0011-event-driven-payment.md) | 결제는 이벤트 기반, 만료 후 결제는 자동 환불 | 수락 |
| [0012](0012-fake-pg.md) | 결제 연동은 가짜 PG로 시작 | 수락 |
| [0013](0013-jwt-behind-bff.md) | 자체 JWT 발급, 토큰은 BFF가 보관 | 수락 |
| [0014](0014-sse-bypasses-bff.md) | SSE는 BFF를 우회해 Gateway에 직접 연결 | 수락 |
| [0015](0015-monorepo.md) | 모노레포 | 수락 |
| [0016](0016-provided-required-packages.md) | 헥사고날 패키지는 provided / required로 명명 | 수락 |
| [0017](0017-payment-owns-payment-events.md) | 결제 계약 이벤트는 payment 모듈이 소유 (booking → payment 단방향) | 수락 |
| [0018](0018-payment-retry-boundaries.md) | PG 호출의 트랜잭션 분리와 미확정 결제·환불 재처리 | 수락 |
| [0019](0019-batch-jobs-in-owning-modules.md) | Batch Job을 데이터 소유 모듈에 둔다 | 수락 |
| [0020](0020-gateway-verification-contracts.md) | Gateway 검증 계약 구체화 | 수락 |
| [0021](0021-realtime-queue-and-sse-contracts.md) | realtime 대기열·SSE 계약 | 수락 |
| [0022](0022-bff-session-and-aggregation-apis.md) | BFF 세션과 조합 API | 수락 |
| [0023](0023-schedule-detail-api.md) | 예매 화면용 회차 조회 API | 수락 |
| [0024](0024-observability-and-load-test.md) | 관측 스택과 부하 테스트 방식 | 수락 |
| [0025](0025-booking-seat-copy-cache.md) | booking 불변 좌석 사본을 요청 좌석 단위로 Redis 캐시 | 수락 |
| [0026](0026-aot-cache-startup.md) | 부하 측정의 AOT 캐시 기동(콜드 + 캐시) | 수락 |
| [0027](0027-scouter-reactive-txid-context-propagation.md) | Scouter WebFlux txid 전파를 context-propagation으로 교체한 패치 에이전트 | 수락 |
| [0028](0028-cold-definition-internal-warmup.md) | 부하 측정의 "콜드" 정의: readiness 전 내부 예열 허용 | 수락 |
| [0029](0029-layered-startup-warmup.md) | 선택 가능한 계층형 내부 예열과 DB·Redis·SSE 격리 | 일부 대체됨 (0030) |
| [0030](0030-startup-warmup-burst-volume.md) | 내부 예열 HTTP 호출량·동시성을 외부 예열 기준으로 확대 | 수락 |
| [0031](0031-warmup-through-real-booking-handler.md) | 내부 예열을 실제 예매 핸들러와 보안 체인 경유로 변경 | 수락 (일부 확장: 0032) |
| [0032](0032-warmup-traffic-mix.md) | 내부 예열을 실제 예매 구간 트래픽 구성(예매·결제·상세·웹훅·PG 조회)으로 혼합 | 수락 |
| [0033](0033-warmup-rate-limiter-path.md) | Gateway 내부 예열에서 rate limiter 경로 실행 | 합의 |
| [0034](0034-platform-admin-role-and-audit.md) | 플랫폼 관리자 역할과 audit 모듈 | 합의 |
| [0035](0035-coupon-module-reservation-saga.md) | coupon 모듈과 예약 Saga | 합의 |
| [0036](0036-settlement-list-price-platform-discount.md) | 정산 정가 기준과 플랫폼 할인 부담 | 합의 |
| [0037](0037-limited-coupon-claim-redis-lua.md) | 선착순 쿠폰 발급 방식(Redis Lua + 동기 DB INSERT) | 합의 |
| [0038](0038-queue-stays-on-redis-not-kafka.md) | 대기열 저장소는 Redis를 유지하고 Kafka로 옮기지 않는다 | 제안 |
| [0039](0039-kafka-externalizes-modulith-events-only.md) | Kafka는 Modulith 이벤트 외부화에만 쓰고 좌석 스트림은 Redis Streams 유지 | 합의 |
| [0040](0040-payment-separate-database-same-instance.md) | payment는 같은 PostgreSQL 인스턴스의 별도 DB로 분리 | 합의 |
| [0041](0041-auth-stays-in-core-sas-deferred.md) | M6에서는 인증 구조를 유지하고 Spring Authorization Server는 후속으로 미룸 | 합의 |
| [0042](0042-contract-module-keeps-packages-and-json-compat-rules.md) | 계약 이벤트는 기존 패키지를 유지한 채 contract 모듈로 옮기고 JSON 형식은 골든 파일로 고정 | 합의 |
| [0043](0043-kafka-externalization-step2-decisions.md) | 모놀리스 상태의 Kafka 외부화(M6 2단계) 구현 결정 | 합의 |
