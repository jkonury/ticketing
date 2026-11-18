# 백엔드 테스트 지침

테스트·검사·E2E·부하 테스트 실행 조건과 명령은 [AGENTS.md](../../AGENTS.md#7-테스트-실행)를 따른다.

## 테스트 종류

- 도메인: 프레임워크 없는 순수 단위 테스트. 상태 전이의 허용·금지 경로를 모두 다룬다.
- 유스케이스: 출력 포트를 가짜 구현(fake)으로 대체한다. mock 남용을 피한다.
- 어댑터와 통합: 테스트가 사용하는 PostgreSQL·Redis·Kafka는 Testcontainers로 검증한다. H2나 embedded Redis를 쓰지 않는다.
- 코어 모듈: `@ApplicationModuleTest`, 이벤트 발행은 `Scenario` / `PublishedEvents`로 검증한다.
- 동시성: 경쟁 요청을 실제로 병렬 실행하고 대상 불변식을 단언한다. 같은 좌석의 확정 경쟁에서는 "성공 1건, DB 확정 1건"을 단언한다.

## 공통 작성 규칙

- 테스트는 given(준비), when(실행), then(검증) 사이에 빈 줄을 둔다. `// given` 같은 주석은 달지 않는다.
- 시간 의존 로직은 `Clock`을 고정해 테스트한다. `Thread.sleep`으로 기다리지 않는다.
- 테스트 이름은 한국어 백틱 문장으로 쓴다 (``fun `만료된 예매는 확정할 수 없다`()``).
- 코루틴 테스트는 `runTest`를 쓴다.
- 단언은 AssertJ로 통일한다 (`assertThat`, `assertThatThrownBy`). JUnit `Assertions`와 섞지 않는다.
- 같은 객체 생성 코드를 두 테스트 클래스 이상이 쓰면 대상 패키지의 `object XxxFixture`로 모은다. 선택 값은 기본 인자로 받는다 (`createBooking(status = ...)`). 한 클래스에서만 쓰면 `private fun` 헬퍼로 둔다.
- 특정 상태의 객체는 도메인 메서드를 호출해 만든다 (`createPending().apply { confirm() }`). 리플렉션이나 랜덤 객체 생성으로 도메인 상태를 주입하면 불변식을 우회하므로 쓰지 않는다. 리플렉션은 영속 후에만 생기는 `id`가 필요할 때로 한정한다.
- 테스트 클래스의 공용 상태가 Spring 빈이 아니면 `private lateinit var` + `@BeforeEach`를 쓸 수 있다. JUnit은 테스트마다 인스턴스를 새로 만든다.
- 엔티티의 컬렉션 불변성, `@field:` 검증 동작처럼 Kotlin 특성에서 오는 규칙은 회귀 방지 테스트를 한 번씩 둔다.

## Spring 테스트 의존성 주입

- Spring 빈은 생성자의 `private val`로 주입한다. 하위 테스트가 사용하는 공통 베이스의 의존성은 필요한 가시성으로 선언하고, 구체 테스트의 생성자에서 전달한다. `@Autowired lateinit var` 필드 주입은 쓰지 않는다.
- Spring 생성자 주입을 사용하는 모듈의 테스트 리소스에 `junit-platform.properties`를 두고 `spring.test.constructor.autowire.mode=all`을 설정한다. 생성자마다 `@Autowired`를 반복하지 않는다.
- `ApplicationEvents`는 테스트 메서드 범위의 의존성이므로 필요한 테스트 메서드의 매개변수로 주입한다.
- `@MockitoBean` 필드는 Spring 빈 대체를 선언하므로 `lateinit var`를 유지할 수 있다. 일반 빈의 필드 주입과 구분한다.

## HTTP 테스트

- MVC HTTP 테스트(`core`, `fake-pg`)는 `MockMvcTester`로 요청을 만들고 `assertThat(result).hasStatus(...)`·`bodyJson()`으로 검증한다. WebFlux HTTP 테스트(`realtime`, `gateway`)는 `WebTestClient`를 쓴다.
- `realtime`과 `gateway`의 통합 테스트는 각각 `RealtimeIntegrationTestSupport`와 `GatewayIntegrationTestSupport`를 재사용한다.

## core 통합 테스트

### Spring 컨텍스트 재사용

- 코어 통합 테스트는 `IntegrationTestSupport`를 상속하고 공통 애플리케이션 클래스·MockMvc·컨테이너 설정을 사용한다. 하위 테스트에 같은 `@SpringBootTest`·`@AutoConfigureMockMvc` 설정을 반복하지 않는다.
- 공통 테스트 설정은 명시적으로 재사용한다. 클래스별 `@Import`, 프로필, 테스트 프로퍼티, 빈 대체는 다른 컨텍스트를 만들 수 있으므로 테스트 동작에 필요한 경우에만 추가한다. Clock·가짜 PG·배치 실패 주입 같은 대체는 `CommonTestConfiguration`의 공용 가변 빈으로 두고 `IntegrationTestSupport`가 매 테스트 전에 초기화한다. 테스트가 필요한 시각·응답은 `@BeforeEach`에서 설정한다. `@MockitoBean`·클래스별 빈 대체는 공용 빈으로 표현할 수 없을 때만 쓴다. 시작 시 데이터를 넣는 seed 프로필은 격리를 유지한다.
- `@DirtiesContext`는 컨텍스트 자체를 복구할 수 없게 변경한 경우에만 쓴다. 테스트 데이터 정리를 대신하는 용도로 사용하지 않는다.
- 같은 컨텍스트의 테스트는 고유 식별자로 데이터를 구분하고(잡처럼 전역을 훑는 결과는 자기 데이터 기준으로 단언한다), 비동기 이벤트 처리가 끝난 뒤 검증한다. 공유 Clock·가짜 구현의 가변 상태는 각 테스트가 필요한 초기 상태로 설정한다.

### Testcontainers 재사용

- 코어 PostgreSQL·Redis·Kafka 컨테이너는 `TestcontainersConfiguration`에서 사용하는 JVM 단위 싱글턴을 공유한다. 테스트 클래스나 컨텍스트마다 컨테이너를 새로 만들지 않는다.
- 공유 컨테이너의 수명은 Spring 빈·JUnit 테스트 클래스의 수명과 분리한다. `@Container`·`@Testcontainers`로 클래스 종료 시 정리하거나, Spring 컨테이너 빈의 종료 처리·`stop()`·`close()`로 공유 컨테이너를 중단하지 않는다. JVM 종료 후 정리는 Testcontainers의 Ryuk에 맡긴다.
- 접속 정보는 공통 `DynamicPropertyRegistrar`에서 등록한다. 서로 다른 컨텍스트는 PostgreSQL 데이터베이스, Redis DB 번호, Kafka 토픽 접두사를 따로 배정해 상태와 메시지를 격리한다.
- 재사용 범위는 같은 테스트 JVM이다. Gradle 실행 간 재사용을 위한 `withReuse(true)`나 개인 환경 설정을 기본 전제로 추가하지 않는다.
- 재사용 구성을 바꾸면 테스트 필터 없이 관련 테스트를 실행하고, 컨텍스트·컨테이너 기동 수와 스위트 시간을 비교한다. 측정 방법과 기존 결과는 [통합 테스트 재사용 개선](../test-performance.md)을 참고한다.

## 이벤트 계약

- `contract`의 `ContractEventSerializationTests`와 `core`의 `ContractEventPublicationSerializationTests`에서 JSON 골든 파일을 공유한다. 계약 이벤트 변경 시 기본 직렬화와 실제 Modulith `EventSerializer`의 호환성을 모두 검증한다. [ADR 0042](../adr/0042-contract-module-keeps-packages-and-json-compat-rules.md)
