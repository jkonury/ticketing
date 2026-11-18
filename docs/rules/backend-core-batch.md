# core-batch 작성 지침

- `core-batch`는 웹 서버 없이 `batch` 프로필로 core 컨텍스트를 띄워 지정한 Job 하나를 실행한다. Kafka 발행은 가능하며 소비 리스너는 기동하지 않는다.

Job 구현은 데이터를 소유한 모듈의 [Batch Job 지침](backend-core.md#batch-job)을 따른다. Job 목록과 파라미터는 [Batch 설계](../architecture/batch.md), Kafka 소비 설정의 근거는 [ADR 0043](../adr/0043-kafka-externalization-step2-decisions.md)을 참고한다.
