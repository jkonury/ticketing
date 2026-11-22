# payment-batch 작성 지침

- `payment-batch`는 웹 서버 없이 `batch` 프로필로 payment 컨텍스트(`PaymentApplication`)를 띄워 지정한 Job 하나를 실행한다. 진입점은 `io.github.ticketing.paymentbatch.PaymentBatchApplication`, 실행 JAR는 `payment-batch.jar`다. Kafka 발행은 가능하며 소비 리스너는 기동하지 않는다.
- 실행: `java -jar payment-batch/build/libs/payment-batch.jar --spring.batch.job.name=paymentReconciliationJob targetDate=<Asia/Seoul 날짜>`
- Job 코드는 payment 앱의 `adapter/provided/batch`에 두고 `@Profile("batch")`를 붙인다. Job 이름·파라미터·대사 규칙은 [Batch 설계](../architecture/batch.md)를 따른다.
- 배치 메타데이터는 `payment_db`의 `batch` 스키마(`V3__create_batch_metadata.sql`)에 둔다. 계정 `search_path`가 `public`이므로 테이블 접두어는 `batch.BATCH_`로 스키마를 한정하고, jOOQ 생성 대상에는 `batch`를 넣지 않는다.
- 웹 전용 빈(예열 러너·작업, 보안 필터 체인)은 `@ConditionalOnWebApplication`으로 batch에서 제외한다. 새 웹 전용 빈도 같은 조건을 붙인다.
- Job 테스트는 `BatchIntegrationTestSupport`(비웹 `batch` 프로필 컨텍스트)를 상속한다.
