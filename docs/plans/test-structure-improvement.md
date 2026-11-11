# 테스트 구조 개선 실행 계획

- 작성일: 2026-11-12
- 상태: Step 5 완료, Step 6 진행 예정
- 기준: [아키텍처](../architecture.md), [로드맵](../roadmap.md), [백엔드 규칙](../rules/backend.md)
- 기존 측정: [통합 테스트 재사용 개선](../test-performance.md)

## 목표와 실행 원칙

- 전체 모듈의 단위 테스트 실행 시간을 컴파일 제외 **10초 이하**로 만든다.
- 개발 중에는 단위 테스트만 실행하고, Stop 훅과 pre-commit에서 전체 통합·구조·품질 검사를 수행한다.
- 운영 저장소는 `JpaRepository` 대신 Spring Data `Repository<T, ID>`를 직접 상속한다. 테스트에서는 직접 작성한 fake를 사용하며 Mockito·MockK는 사용하지 않는다.
- JPA 쓰기·jOOQ 조회 설계는 유지한다. 공통 `SimpleRepository`를 추가하거나 EntityManager 직접 구현으로 전환하지 않는다.
- 구현 subagent 모델은 모두 `gpt-6.1-terra`로 지정한다. 상위 모델을 사용하지 않는다.
- 주 에이전트는 설계 일관성, 작업 배정, 결과 통합과 최종 검증을 담당한다. subagent에는 담당 경로와 변경 금지 영역을 명시한다.
- 동시 subagent는 최대 3개로 제한한다. 파일 소유권이 겹치지 않는 구현만 병렬 진행한다. Gradle 검사와 성능 측정은 전체 작업 디렉터리에서 순차 실행한다.
- 이 문서는 향후 구현 계획이다. 문서 작성 자체는 구현 단계의 착수나 완료를 의미하지 않는다.

## 진행 상태

- [x] Step 1. 기준선 측정과 설계 결정 기록
- [x] Step 2. 단위·통합 테스트 실행 경로 분리
- [x] Step 3. 운영 저장소 최소화
- [x] Step 4. core 단위 테스트 전환
- [x] Step 5. gateway·realtime·fake-pg 단위 테스트 전환
- [ ] Step 6. 통합 테스트 컨텍스트 통합
- [ ] Step 7. Stop 훅·git hook 연결
- [ ] Step 8. 성능 검증과 최종 통합

## Step 1. 기준선 측정과 설계 결정 기록

**담당:** 조사 subagent 1개. **선행 조건:** 없음.

- 현재 테스트를 단위·통합·구조·문서 생성·E2E로 분류하고 각 시나리오의 이동 대상을 기록한다.
- 전체 테스트 수, 실행 시간, Spring 컨텍스트·컨테이너 기동 수, mock 사용 위치, 저장소 메서드 사용처를 조사한다.
- 기존 스크립트로 기준선을 측정한다. 문서 생성 테스트는 추적 파일을 변경하지 않도록 출력 위치를 분리한다.
- 이 문서에 Step별 진행 상태와 검증 근거를 기록한다.
- 새 ADR에 Repository 최소 인터페이스와 테스트 실행 정책을 기록한다. 아키텍처·백엔드 규칙의 기존 JpaRepository 전용 사용 규칙을 합의한 정책으로 변경한다.
- 로드맵에는 M4 기능 개발과 별개인 이번 개선 작업을 등록한다.

**완료 기준:** 기존 검증의 이동 목록, 성능 기준선, 저장소별 최소 인터페이스와 설계 문서 변경이 확정되어 있다.

### 조사 결과

- 테스트별 원래 시나리오와 이동 대상은 [시나리오 목록](./test-scenario-inventory.md)에 기록했다. 통합 테스트에 있는 서비스 분기는 단위 테스트로도 옮기되 실제 인프라·HTTP·트랜잭션·이벤트·동시성 검증은 유지한다. `core`의 `e2e` 패키지는 같은 JVM의 통합 테스트이며 별도 `e2e` 모듈과 구분한다.
- mock 빈은 `PaymentWebhookHttpTests`, `BookingPaymentFlowTests`, `BookingApiIntegrationTests`의 세 클래스(총 5개 필드)에 있다. 직접 작성한 fake로 대체하되 실제 이벤트 계약을 유지한다.
- 다음 표는 운영 호출 기준이다. 기존 통합 테스트에서만 사용하는 `ScheduleRepository.save`, `ScheduleRepository.findById`, `VenueRepository.flush`는 운영 저장소를 넓히지 않도록 실제 DB 검증을 유지하면서 테스트를 조정한다.

| 저장소 | 필요한 메서드와 호출처 |
| --- | --- |
| MemberRepository | `findByEmail`, `existsByEmail`, `findById`, `saveAndFlush`: AuthService·MemberService |
| RefreshTokenRepository | `findByTokenHash`, `revokeAllByMemberId`, `save`: AuthService. 비관적 잠금·수정 쿼리 유지 |
| VenueRepository | `existsByName`, `save`, `findById`: CatalogSeedRunner·CatalogRegistrationService |
| PerformanceRepository | `save`, `findById`: CatalogRegistrationService |
| ScheduleRepository | `existsByPerformanceIdAndStartsAt`, `saveAndFlush`: CatalogRegistrationService |
| BookingJpaRepository | `findWithSeatsById`, `findWithSeatsByMemberIdAndIdempotencyKey`, `saveAndFlush`: BookingPersistenceAdapter. fetch 쿼리 유지 |
| ScheduleSeatCopyJpaRepository | `findByKeyScheduleIdAndKeySeatIdIn`, `saveAll`: ScheduleSeatPersistenceAdapter |
| PaymentJpaRepository | `findByOrderId`, `findByIdempotencyKey`, `findByPgTransactionId`, `findById`, `saveAndFlush`: PaymentPersistenceAdapter |

기존 호출의 반환 타입과 저장·flush 시점을 유지한다. `saveAndFlush` 등 기본 구현과 잠금은 Step 3 통합 검증으로 확인한다.

## Step 2. 단위·통합 테스트 실행 경로 분리

**담당:** 빌드 subagent 1개. **선행 조건:** Step 1.

- 공통 convention plugin에 `test`와 `integrationTest` 구성을 추가한다.
- `src/test`에는 단위 테스트, `src/integrationTest`에는 Spring·인프라·Modulith·ArchUnit 검증을 배치한다.
- 테스트 지원 코드도 사용 범위에 맞게 분리한다. 통합 테스트는 필요한 단위 테스트 fixture를 재사용할 수 있지만 반대 의존은 허용하지 않는다.
- `check`가 단위·통합·기존 정적 검사를 포함하도록 연결한다.
- 문서 생성은 명시적 작업으로 분리한다. 별도 프로세스 E2E는 기존 실행 방식을 유지하고 core·gateway·realtime 흐름 변경 시 실행한다.
- `run-tests.sh`의 기본 실행은 전체 단위 테스트로 하고 작업별 결과·실패 요약을 지원한다. 사용자 지정 없는 테스트 필터는 추가하지 않는다.

**완료 기준:** 기본 개발 명령이 Spring·컨테이너를 기동하지 않으며, 분리된 검증을 명시적으로 실행할 수 있다.

## Step 3. 운영 저장소 최소화

**담당:** 저장소 subagent 1개. **선행 조건:** Step 2.

- 8개 저장소의 `JpaRepository` 상속을 `Repository<T, ID>` 직접 상속으로 변경한다.
- 호출에 필요한 저장·조회·flush 메서드만 선언한다. fake 구현 편의를 위해 사용하지 않는 CRUD 메서드를 추가하지 않는다.
- 기존 저장·flush 시점, JPQL, 비관적·낙관적 잠금, `Persistable`, 변경 감지와 예외 변환을 보존한다.
- DB 유니크 제약과 기존 트랜잭션 경계를 유지한다. HTTP API와 DB 스키마는 변경하지 않는다.
- Spring Data가 최소 인터페이스의 메서드를 실제로 구현하는지 프로젝트 버전에 맞는 공식 문서와 통합 검증으로 확인한다.

**완료 기준:** 운영 코드에 `JpaRepository` 상속이 없고 기존 영속성 계약이 유지된다.

## Step 4. core 단위 테스트 전환

**담당:** 소유권이 겹치지 않는 subagent 최대 3개. **선행 조건:** Step 3.

| 담당 | 구현 범위 |
|---|---|
| A | booking 서비스·포트 fake·예매 단위 테스트 |
| B | payment·settlement 서비스·포트 fake·단위 테스트 |
| C | member·catalog·notification의 최소 저장소 fake·단위 테스트 |

- Spring 통합 테스트에 섞인 비즈니스 분기 검증을 객체 직접 생성 방식으로 옮긴다.
- 헥사고날 서비스는 출력 포트의 fake를 사용한다. member·catalog는 최소 저장소 인터페이스를 직접 구현한 fake를 사용한다.
- 저장·조회·멱등성·실패 주입에 필요한 fake를 테스트 소스에 구현한다. 각 테스트는 새로운 fake 인스턴스를 사용한다.
- 고정·가변 Clock과 이벤트 수집용 fake를 사용한다. Spring 이벤트 전달·트랜잭션 보장은 통합 테스트로 검증한다.
- 회원·인증 서비스 단위 테스트에는 테스트용 PasswordEncoder를 사용한다. 실제 해시·보안 연동 검증은 통합 테스트에 남긴다.
- 기존 성공·실패·보상·재시도 시나리오를 유지한다. 실제 DB 제약이나 잠금을 fake로 검증했다고 간주하지 않는다.

**완료 기준:** core의 비즈니스 규칙을 Spring 없이 검증하고 기존 시나리오 이동 목록에 누락이 없다.

## Step 5. gateway·realtime·fake-pg 단위 테스트 전환

**담당:** 모듈별 subagent 최대 3개. **선행 조건:** Step 3.

- Redis·HTTP·예약 실행과 로직 사이에 필요한 최소 인터페이스를 추가한다. Redis 명령·Lua 실행은 실제 어댑터에 남긴다.
- 직접 작성한 저장소·전송기·스케줄러 fake로 대기열, 입장, 토큰 검증, 결제 시나리오와 재시도를 검증한다.
- 코루틴은 `runTest`와 가상 시간으로 검증한다. 단위 테스트에서 실제 대기·서버 기동·네트워크 요청을 제거한다.
- MockMvc·WebTestClient 등 HTTP 테스트 도구는 유지하되 Mockito·MockK와 mock 빈은 제거한다.
- Step 4와 병렬 진행할 때 core와 공통 빌드 파일은 수정하지 않는다. 전체 동시 subagent 수는 3개를 넘기지 않는다.

**완료 기준:** 모든 모듈의 개발용 단위 테스트가 외부 인프라 없이 실행된다.

## Step 6. 통합 테스트 컨텍스트 통합

**담당:** 통합 테스트 subagent 1개. **선행 조건:** Step 4·5.

- 애플리케이션별 공통 베이스에 설정·Clock·fake PG를 모은다. 테스트별 불필요한 `@Import`, 프로퍼티와 빈 대체를 제거한다.
- batch·seed·다중 인스턴스처럼 동작 차이가 필요한 컨텍스트는 유지하고 분리 이유를 기록한다.
- 통합 테스트 JVM은 클래스마다 재시작하지 않는다. 기존 JVM 단위 컨테이너 공유와 컨텍스트별 데이터 격리를 유지한다.
- 고유 데이터 식별자를 사용하고 비동기 처리가 끝난 후 공유 fake·Clock 상태를 초기화한다.
- 실제 DB·Redis·HTTP·보안·이벤트·Batch 연동 검증을 유지한다. 테스트 데이터 정리를 위해 `@DirtiesContext`를 사용하지 않는다.
- 사용하지 않는 mock 의존성과 mock 허용 지침을 제거한다. 단위 테스트 경로에 Spring 기동·컨테이너·mock 사용이 들어오지 않도록 검사를 추가한다.

**완료 기준:** 전체 통합 테스트가 통과하고 컨텍스트 기동 수와 재사용 결과가 기록되어 있다.

## Step 7. Stop 훅·git hook 연결

**담당:** 훅 subagent 1개. **선행 조건:** Step 6.

- Stop 훅과 pre-commit에서 전체 `check`를 실행하도록 공통 검증 경로를 사용한다.
- Kotlin뿐 아니라 빌드·리소스·테스트 스크립트·훅 변경도 검증 대상으로 포함한다.
- 공통 잠금으로 Gradle 검사를 직렬화한다. 잠금 대기는 검증 성공으로 처리하지 않는다.
- Stop 훅의 재진입이 실패를 숨기지 않도록 변경 상태별 결과를 기록한다. 동일 상태의 성공만 재사용하며 변경 후에는 재검증한다.
- 실패 시 테스트 이름·실패 메시지·로그 경로를 보고하고 종료·커밋을 차단한다.
- AGENTS.md, 백엔드 규칙과 훅 안내를 실제 실행 방식에 맞게 갱신한다.

**완료 기준:** 성공·실패·재진입·변경 감지·동시 실행 상황을 검증했고 검사 우회가 없다.

## Step 8. 성능 검증과 최종 통합

**담당:** 주 에이전트. 병목 수정은 해당 subagent에 재배정한다. **선행 조건:** Step 7.

- 컴파일을 완료한 뒤 전체 단위 테스트 작업만 캐시·UP-TO-DATE 없이 재실행한다.
- 각 테스트 JVM의 기동부터 종료까지 시간을 합산한다. JVM 내부 테스트 병렬화 없이 **연속 3회 각각 10초 이하**를 만족해야 한다.
- Gradle 구성·컴파일·jOOQ 생성 시간은 별도 기록한다. JUnit XML의 테스트 메서드 시간 합계만으로 성능 달성을 판정하지 않는다.
- 초과하면 느린 테스트·클래스 초기화·실제 대기·불필요한 프로세스 기동을 분석하고 수정한다. 테스트 제외·삭제·비활성화로 기준을 맞추지 않는다.
- 전체 `check` 후 관련 E2E와 문서 검사를 순차 실행한다. Kotlin 변경에 필요한 `ktlintCheck detekt`를 포함한다.
- 실제 통합 검증에 좌석 중복 확정 방지, 저장·flush·잠금, 인증 동시 갱신, 결제 멱등성, 이벤트, Batch 재실행, SSE 다중 인스턴스가 포함됐는지 확인한다.
- 전후 테스트 수·시나리오 대응, 시간, 컨텍스트·컨테이너 기동 수, 측정 명령과 환경을 성능 문서에 기록한다.
- 이 문서의 체크박스와 로드맵은 실제 검증을 통과한 항목만 완료 처리한다. 실패는 실패한 그대로 보고한다.

**최종 완료 기준:** 전체 단위 테스트 10초 제한, 전체 통합·구조·품질 검사, 관련 E2E, 훅 동작 검증을 모두 만족한다. 사용자 요청에 따라 각 Step 완료 시 커밋한다.

## 참고 근거

- [Spring Data 저장소 최소 인터페이스 정의](https://docs.spring.io/spring-data/jpa/reference/repositories/definition.html)
- [Spring 테스트 컨텍스트 캐시](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)
- [Gradle JVM 테스트 구성](https://docs.gradle.org/current/userguide/java_testing.html)

## 실행 기록

### Step 1

- 기존 실행 로그와 JUnit XML을 재확인했다. 270개 테스트 전체 통과, 벽시계 88.26초, 스위트 합계 112.184초, 컨텍스트 23회, PostgreSQL 1개·Redis 3개다. 상세는 [성능 기록](../test-performance.md)과 [시나리오 이동 목록](./test-structure-inventory.md)을 따른다.
- 기준선 로그: `build/run-tests/gradle-test-out.txt`. 기존 실행이 생성한 다이어그램 변경은 복구된 상태다. 이후 문서 생성은 별도 출력 경로와 작업으로 분리한다.
- ADR 0022와 아키텍처·백엔드 규칙·로드맵을 정합성 확인했다. Spring Data JPA 4.1.2 공식 문서의 선택적 CRUD 서명 지원을 확인했다.
- 문서 링크·경로 검사 통과. Kotlin 변경이 없어 품질·E2E 재실행 대상은 없다.

### Step 2

- 기본 `test`는 단위 61개(core 57·fake-pg 4), `integrationTest`는 통합·구조 208개, 명시적 `:core:documentationTest`는 문서 1개다. 기존 270개를 모두 유지한다. gateway·realtime 단위 테스트는 Step 5에서 추가한다.
- 단위 테스트, 전체 `check`(ktlint·detekt 포함), 문서 생성과 문서 링크·경로 검사 통과. 문서 기본 출력은 `core/build/modulith`이며 추적 다이어그램은 바뀌지 않는다. 서비스 흐름 변경이 없어 E2E는 생략했다.
- 초기 Kotlin daemon 경로 권한·메타스페이스 부족·캐시 잠금은 권한 상승 및 `-Pkotlin.compiler.execution.strategy=in-process --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=1g"`로 해결했다. 통합 소스 세트의 internal 접근과 빌드 포맷 오류를 수정한 후 통과했다. 검증은 이 옵션을 붙여 전용 test/check 스크립트로 순차 실행했다.
- 로그: `build/check-summary/gradle-out.txt`, `build/run-tests/gradle-test-out.txt`(마지막 문서 작업).

### Step 3

- 운영 저장소 8개를 `Repository<T, ID>` 직접 상속과 실제 호출 메서드로 바꿨다. JPQL·비관적 잠금·saveAndFlush·saveAll 계약과 기존 엔티티 신규 판별은 유지한다. 운영 코드에 JpaRepository 상속은 없다.
- 테스트만 쓰던 Venue flush·Schedule save/findById는 운영 인터페이스에 추가하지 않았다. 카탈로그 영속 검증은 기존 saveAndFlush·테스트 EntityManager 재조회·트랜잭션 커밋 시 제약 검증으로 유지했다. 직접 flush 예외 타입 차이로 첫 실행에서 실패한 뒤 기존 DataIntegrityViolationException 검증을 복원했다.
- `./scripts/agent/test/run-tests.sh :core:integrationTest` 141개와 `./scripts/agent/test/check-summary.sh ktlintCheck detekt` 통과(Step 2의 환경 옵션 적용). 공식 Spring Data JPA 4.1.2 문서와 실제 저장·잠금·동시성 통합 검증으로 확인했다. 모듈 의존·API·스키마·서비스 흐름 변경이 없어 E2E는 생략했다.
- 로그: `build/run-tests/gradle-test-out.txt`, `build/check-summary/gradle-out.txt`.

### Step 4

- booking·payment/settlement·member/catalog/notification을 분담해 서비스 직접 생성과 포트·최소 저장소 fake로 비즈니스 분기를 검증했다. 회원 단위 테스트는 빠른 PasswordEncoder를 사용한다. 각 테스트의 fake와 Clock은 독립적이다.
- core 단위 92개(기존 57개에서 35개 증가), 통합·구조 141개를 모두 통과했다. `:core:check`로 ktlint·detekt까지 확인했다. 최초 PasswordEncoder null 허용 시그니처 컴파일 오류를 수정한 후 통과했다. 테스트 코드만 바뀌어 E2E는 생략했다.
- [단위 시나리오 대응](./test-structure-inventory.md#추가한-서비스-단위-검증-대응), [fake 대체 경계와 통합 유지 사유](./test-structure-boundaries.md)를 기록했다. 통합 리스너 MockitoBean 5개는 다른 모듈 동작을 차단하는 용도라 Step 6에서 전환한다.
- 검증 명령: 전용 test 스크립트 `:core:test`, check 스크립트 `:core:check`(Step 2 환경 옵션). 로그: `build/run-tests/gradle-test-out.txt`, `build/check-summary/gradle-out.txt`.

### Step 5

- gateway의 입장 토큰·SSE 티켓·rate limit, realtime의 대기열·입장, fake-pg의 웹훅 전송·스케줄 경계를 최소 인터페이스와 실제 어댑터로 나눴다. Redis 명령·Lua·HTTP·서명·스케줄 시각은 기존 동작을 유지한다.
- 전체 단위 117개(core 92·gateway 5·realtime 10·fake-pg 10) 및 전체 `check`(통합·구조 208개와 품질) 통과. 문서 생성 1개는 별도다. 기존 270개를 모두 유지하고 단위 56개를 추가했다. Spring·서버·컨테이너 기동 없는 단위 검증으로 확인했다.
- realtime의 nullable StreamId 비교 단언 컴파일 오류를 수정했다. 다른 모듈 결과 XML이 있는 상태에서 컴파일 실패 요약이 누락되는 run-tests 스크립트도 Gradle 오류를 출력하도록 보완했다.
- 명령: 전용 test 스크립트 기본 실행, check 스크립트 `check`(Step 2 환경 옵션). 로그: `build/run-tests/gradle-test-out.txt`, `build/check-summary/gradle-out.txt`. 관련 E2E는 Step 8 최종 통합 검증에서 실행한다. [대체 경계](./test-structure-boundaries.md)에 실제 연동 검증을 남긴 이유를 기록했다.
