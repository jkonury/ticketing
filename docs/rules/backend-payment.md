# payment 작성 지침

- `io.github.ticketing.PaymentApplication`을 진입점으로 하는 별도 WebMVC 앱이다. Virtual Thread를 사용하고, 로컬 포트는 `18085`, 실행 JAR는 `payment.jar`다.
- 실행: `./gradlew :payment:bootRun` 또는 `./scripts/dev/run.sh --payment`(`--all`에 포함). core와 함께 떠야 결제 흐름이 동작하며, 조회는 Gateway(`/api/v1/payments/me`)를 거치고 fake-pg 웹훅은 이 앱(18085)을 직접 호출한다. [gateway](backend-gateway.md), [fake-pg](backend-fake-pg.md)
- `common` 자동 구성에서 Kafka 토픽, 재시도·DLT, 이벤트 발행 지표를 가져온다.
- 같은 PostgreSQL 인스턴스에서 전용 계정과 `payment_db`를 쓴다. `.env.example`의 `TICKETING_PAYMENT_POSTGRES_*` 값을 설정한다. 새 볼륨은 Compose 초기화에서 준비하고, 기존 볼륨은 `./scripts/db/ensure-payment-db.sh`로 준비한다. 분리 전 core DB의 결제 데이터는 `./scripts/db/migrate-payment-data.sh`(점검 창에서 `pg_dump` 이전과 건수·체크섬 대조, `--verify-only`로 대조만)로 옮기며 절차는 [런북 §9](../runbook.md#9-결제-db-이전-core-db-payment-스키마--payment_db)를 따른다. [ADR 0040](../adr/0040-payment-separate-database-same-instance.md)
- 전용 계정이 결제 DB에 접속할 때 `search_path`는 `public`이다. 앱 연결은 Hikari의 기본 스키마도 `public`으로 지정한다.
- 앱의 Flyway가 `payment` 스키마와 `public.event_publication`을 관리한다. jOOQ는 같은 마이그레이션을 임시 PostgreSQL에 적용해 `payment` 스키마의 코드를 `build/` 아래의 `ticketing.jooq.payment` 패키지에 생성한다.
- 통합 테스트는 JVM 단위로 PostgreSQL·Kafka Testcontainers를 공유하고, 컨텍스트마다 DB와 Kafka 토픽 접두사를 분리한다. [백엔드 테스트 지침](backend-testing.md#testcontainers-재사용)
- 결제 조회는 core와 같은 공유 비밀로 HS256 JWT를 검증하고 `role` 클레임의 USER 권한을 요구한다. fake-pg 웹훅은 JWT 없이 받고 요청 서명을 검증한다.
- 예열을 켜면 `ApplicationRunner`가 readiness 전에 제한 시간 동안 결제와 웹훅 경로를 롤백 트랜잭션으로 실행하고 PG 거래를 읽는다. 예열이 실패해도 앱은 기동한다.
- 결제·환불 요청의 Kafka 리스너 그룹은 `ticketing-payment`를 유지해 전환 시 core가 커밋한 오프셋을 이어받는다.
- `batch` 프로필은 결제 대사 Job 전용으로 웹 없이 기동한다. 실행은 [payment-batch](backend-payment-batch.md)가 맡는다.
