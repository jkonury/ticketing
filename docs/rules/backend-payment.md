# payment 작성 지침

- `io.github.ticketing.PaymentApplication`을 진입점으로 하는 별도 WebMVC 앱이다. Virtual Thread를 사용하고, 로컬 포트는 `18085`, 실행 JAR는 `payment.jar`다.
- `common` 자동 구성에서 Kafka 토픽, 재시도·DLT, 이벤트 발행 지표를 가져온다.
- 같은 PostgreSQL 인스턴스에서 전용 계정과 `payment_db`를 쓴다. `.env.example`의 `TICKETING_PAYMENT_POSTGRES_*` 값을 설정한다. 새 볼륨은 Compose 초기화에서 준비하고, 기존 볼륨은 `./scripts/db/ensure-payment-db.sh`로 준비한다. [ADR 0040](../adr/0040-payment-separate-database-same-instance.md)
- 전용 계정이 결제 DB에 접속할 때 `search_path`는 `public`이다. 앱 연결은 Hikari의 기본 스키마도 `public`으로 지정한다.
- 앱의 Flyway가 `payment` 스키마와 `public.event_publication`을 관리한다. jOOQ는 같은 마이그레이션을 임시 PostgreSQL에 적용해 `payment` 스키마의 코드를 `build/` 아래의 `ticketing.jooq.payment` 패키지에 생성한다.
- 통합 테스트는 JVM 단위로 PostgreSQL·Kafka Testcontainers를 공유하고, 컨텍스트마다 DB와 Kafka 토픽 접두사를 분리한다. [백엔드 테스트 지침](backend-testing.md#testcontainers-재사용)
