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
| [0022](0022-test-structure-and-repositories.md) | 최소 저장소 인터페이스와 테스트 실행 정책 | 수락 |
