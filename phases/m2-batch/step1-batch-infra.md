# Step 1: batch-infra

- model: 기본 / reasoning_effort: high

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `core/build.gradle.kts`, `core-batch/build.gradle.kts`
- `core-batch/src/main/kotlin/io/github/ticketing/batch/CoreBatchApplication.kt`, `core-batch/src/main/resources/application.yml`
- `core/src/main/resources/application.yml`
- `core/src/main/kotlin/io/github/ticketing/TicketingApplication.kt`
- `core/src/test/kotlin/io/github/ticketing/IntegrationTestSupport.kt`, `core/src/test/kotlin/io/github/ticketing/TestcontainersConfiguration.kt`
- `docs/rules/backend.md § Batch (core-batch)`, `§ 테스트`
- `docs/architecture.md § 9. Batch` (step 0에서 갱신됨)

## 작업

Spring Batch를 core에 들이고, `core-batch`를 core 컨텍스트 런처로 바꾼다. Job은 아직 만들지 않는다.

**Spring Boot 4.1.2 / Spring Batch 6.x 기준이다. 스타터 이름, 설정 키, `JobRepository`·`JobOperator`·테스트 유틸의 패키지와 API는 Spring Batch 5에서 많이 바뀌었으므로 기억이 아니라 공식 문서(docs.spring.io의 Boot 4 Batch 절, Spring Batch 6 레퍼런스·마이그레이션 가이드)로 확인하라.**

### 1. 의존성

- `core/build.gradle.kts`: JDBC 기반 JobRepository를 쓰는 Boot Batch 스타터(Boot 4에서 JDBC용 스타터가 분리됐는지 확인)와 테스트용 `spring-batch-test`를 추가한다. 버전은 Boot BOM을 따른다.
- `core-batch/build.gradle.kts`: 기존 `implementation(project(":core"))`를 유지한다. 필요하면 실행에 필요한 런타임 의존만 더한다.

### 2. 메타데이터 스키마: `core/src/main/resources/db/migration/V7__create_batch_metadata.sql`

- `CREATE SCHEMA batch;` 후 Spring Batch 6 공식 `schema-postgresql.sql`의 테이블·시퀀스를 `batch.` 스키마로 옮겨 만든다. 공식 DDL과 컬럼이 달라지면 안 된다(사용 중인 spring-batch-core jar 안의 `org/springframework/batch/core/schema-postgresql.sql`과 대조).
- JobRepository 테이블 접두사를 `batch.BATCH_`로 설정한다.
- jOOQ 코드 생성 대상(`core/src/jooqGenerator/.../JooqCodegen.kt`의 `SCHEMAS`)에 `batch`를 넣지 않는다.

### 3. 설정

- `core/src/main/resources/application.yml`: Job 자동 실행 끔, Batch 스키마 자동 초기화 끔(`never`), 테이블 접두사.
- `core/src/main/resources/application-batch.yml` (신규): `spring.application.name: core-batch`, 웹 없음, Job 자동 실행 켬(`spring.batch.job.name`으로 하나만 실행), `spring.modulith.events.republish-outstanding-events-on-restart: false`.
  - republish를 끄는 이유: Batch 프로세스가 짧게 떠 있다 꺼지면서 웹 앱이 처리할 미완료 이벤트를 가져가 처리하다 끊기는 것을 막는다.
- `core-batch/src/main/resources/application.yml`은 **삭제**한다. 이유: 클래스패스에 `application.yml`이 둘이면 먼저 찾은 하나만 로드되어 core의 데이터소스 설정이 빠진다.

### 4. 런처: `core-batch/src/main/kotlin/io/github/ticketing/batch/CoreBatchApplication.kt`

```kotlin
// @SpringBootApplication 없이 core의 TicketingApplication을 batch 프로필·웹 없음으로 실행하고
// Job 종료 코드를 프로세스 종료 코드로 돌려준다.
fun main(args: Array<String>)
```

- `bootJar`의 `mainClass`(`io.github.ticketing.batch.CoreBatchApplicationKt`)는 유지한다.
- 실행 예: `./gradlew :core-batch:bootRun --args='--spring.batch.job.name=<jobName> targetDate=2026-11-10'`. 이 형식을 파일 주석이 아니라 `docs/architecture.md § 9. Batch`에 "실행" 한 줄로 적는다.

### 5. 공통 keyset reader: `core/src/main/kotlin/io/github/ticketing/KeysetItemReader.kt`

여러 모듈의 Job이 jOOQ로 chunk 단위 읽기를 하도록 루트 패키지에 둔다(루트 패키지는 Modulith 모듈이 아니므로 모든 모듈이 쓸 수 있다).

```kotlin
class KeysetItemReader<T : Any, K : Comparable<K>>(
    name: String,
    pageSize: Int,
    keyOf: (T) -> K,
    fetchPage: (afterKey: K?, limit: Int) -> List<T>,   // 각 모듈이 jOOQ로 구현
) : ItemStreamReader<T>
```

- 마지막으로 반환한 키를 `ExecutionContext`에 저장하고 `open`에서 복원한다. 재시작 시 이어서 읽기 위함이다. 키 타입은 `Long`·`LocalDate` 등 `ExecutionContext`에 저장 가능한 값으로 제한해도 된다.
- 한 번에 `pageSize`건만 메모리에 둔다.

### 6. 테스트 기반

- `core/src/test/kotlin/io/github/ticketing/BatchIntegrationTestSupport.kt`: `IntegrationTestSupport`를 상속하고 `batch` 프로필을 켜되 Job 자동 실행은 끈다(여러 Job이 있으면 이름 없이 자동 실행이 실패한다). 이후 step의 Job 테스트는 모두 이 클래스를 상속해 한 컨텍스트를 공유한다.
- `core/src/test/kotlin/io/github/ticketing/BatchInfrastructureTests.kt` (`BatchIntegrationTestSupport` 상속): JobRepository가 `batch` 스키마 테이블을 쓰는지 확인한다(예: 테스트용 Job 인스턴스를 만들고 조회).
- `core/src/test/kotlin/io/github/ticketing/KeysetItemReaderTests.kt`: 순수 단위 테스트. 페이지 경계, 빈 결과, `ExecutionContext` 저장 후 새 reader에서 이어 읽기.

## Acceptance Criteria

```bash
test -f core/src/main/resources/db/migration/V7__create_batch_metadata.sql
test -f core/src/main/resources/application-batch.yml
test ! -e core-batch/src/main/resources/application.yml
! rg -q '@SpringBootApplication' core-batch/src
rg -q 'spring-batch-test' core/build.gradle.kts
./scripts/agent/test/run-tests.sh :core:test --tests "*BatchInfrastructureTests" --tests "*KeysetItemReaderTests" --tests "*ModularityTests" --tests "*TicketingApplicationTests"
./gradlew :core-batch:bootJar -q
./scripts/agent/test/check-summary.sh :core:ktlintCheck :core:detekt :core-batch:ktlintCheck :core-batch:detekt
```

## 검증 절차

1. `run-tests.sh :core:test --tests "*BatchInfrastructureTests"`부터 통과시킨 뒤 위 AC 전체를 실행한다.
2. Docker가 떠 있으면 `./gradlew :core-batch:bootRun`을 Job 이름 없이 실행해 컨텍스트가 뜨고 웹 포트를 열지 않은 채 종료되는지 한 번 확인한다(AC는 아님). 확인 결과를 완료 보고에 적는다.

## 금지사항

- `core-batch`에 `@SpringBootApplication`이나 `io.github.ticketing.batch` 패키지의 컴포넌트를 두지 마라. 이유: core의 컴포넌트 스캔(`io.github.ticketing`)에 걸려 `batch`라는 Modulith 모듈이 생기고 설정이 중복된다.
- `spring.batch.jdbc.initialize-schema`로 테이블을 만들지 마라. 이유: 스키마 변경은 Flyway로만 한다.
- 웹 앱(`core`) 기본 설정에서 Job 자동 실행을 켜지 마라. 이유: 웹 앱이 뜰 때마다 Job이 실행된다.
- 이 step에서 실제 Job(정산, 만료 등)을 만들지 마라. 이유: 각 모듈 step의 범위다.
- 적용된 마이그레이션(V1~V6)을 수정하지 마라.
- 기존 테스트를 깨뜨리지 마라.
