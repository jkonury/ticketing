# 백엔드 지침 개선 반영 계획

- 작성일: 2026-11-12
- 상태: 완료 (보관). 규칙은 [core 작성 지침](../../rules/backend-core.md)과 [백엔드 테스트 지침](../../rules/backend-testing.md)에 반영했다
- 출처: 외부 Kotlin 프로젝트 작성 가이드(Spring Boot 3.5 + 헥사고날, 단일 애그리거트 구조)를 이 저장소 기준으로 검토한 결과
- 적용 대상은 core의 JPA 엔티티·API 규칙과 테스트 공통 작성 규칙이다. [core 작성 지침](../../rules/backend-core.md) · [백엔드 테스트 지침](../../rules/backend-testing.md#공통-작성-규칙)

## 1. 반영 범위 결정

사용자와 합의한 내용이다.

| 항목 | 결정 |
|---|---|
| 가이드 반영 범위 | Kotlin·테스트 규칙만 흡수. 모듈 우선 패키지, 도메인/JPA 엔티티 분리(booking·payment), JPA 쓰기·jOOQ 조회, Modulith 이벤트는 현행 유지 |
| `!!` | 전면 금지 유지. 가이드의 경계 허용 예외는 받지 않는다 |
| 검증·예외 | 컨트롤러 경계 Bean Validation + `DomainException(code)` 유지. 서비스 포트 `@Validated`와 조회 실패 `IllegalArgumentException`은 도입하지 않는다 |
| `internal` + ArchUnit `$` 규칙 | 도입하지 않는다. 계층형 모듈은 `protected set`, 헥사고날 모듈은 도메인/JPA 분리로 충분하다고 본다 |
| 계획 문서 위치 | `docs/plans/` |

### 가이드에서 채택하지 않은 항목과 이유

| 가이드 내용 | 채택하지 않는 이유 |
|---|---|
| `domain`의 `@Entity`를 도메인 모델로 사용 | ADR 0004: 헥사고날 모듈은 `domain`에 Spring·JPA import 금지 |
| `AbstractEntity` 공통 상위 클래스 | "전역 유틸 모듈을 만들지 않는다" 규칙, 엔티티가 6개뿐이라 `equals`/`hashCode` 중복이 작다 |
| 식별자 `Long?` + `id!!` | `!!` 금지, 식별자는 value class |
| 패키지 `domain/application/adapter` 최상위 | Modulith 모듈 우선 구조(ADR 0016) |
| H2, Instancio, mockito-kotlin | Testcontainers 원칙, fake 우선 원칙. 의존성을 새로 늘릴 이유가 없다 |
| 테스트 메서드 camelCase | 한국어 백틱 문장 규칙 유지 |
| 베이스 클래스 `@Autowired lateinit var` | 필드 주입 금지 규칙 유지 |
| Spring Boot 3.5·Hibernate 6.6 기준 확인 결과(✅) | 이 저장소는 Boot 4.1.2, Kotlin 2.3.21이다. 가이드의 ✅는 증거로 삼지 않고 아래 단계에서 직접 검증한다 |

## 2. 현재 코드 점검 결과

| 지침 항목 | 현황 | 조치 |
|---|---|---|
| `hashCode` 클래스 기준 상수 | `PaymentJpaEntity`는 충족. `member`의 `Member`, `RefreshToken`, `catalog`의 `Venue`, `Schedule`, `Performance`, `VenueSeat`은 `id?.hashCode() ?: 0` | 단계 1 |
| `protected set` | `member`·`catalog`의 `domain` 엔티티는 공개 `var`(`var name` 등)가 약 30곳 | 단계 2 |
| 컬렉션 불변 노출 | `Venue.seats`가 `_seats`를 그대로 반환해 캐스팅으로 수정 가능. `Booking.seats`는 도메인 모델이라 영속과 무관 | 단계 3 |
| `@field:` | `SignUpRequest` 등은 이미 사용. 컴파일러 옵션 `-Xannotation-default-target=param-property`가 켜져 있어 동작이 가이드(3.5 기준)와 다를 수 있다 | 단계 4 (검증) |
| 도메인 예외 | `domain`에서 `IllegalStateException`·`check` 직접 사용 0건. `ApiExceptionHandler`에 `IllegalState`·`IllegalArgument` 핸들러는 없다 | 확인만 (단계 4) |
| 단언 통일 | 대부분 AssertJ. `BookingTests`, `ModularityTests`, `TicketingApplicationTests`와 `fake-pg` 테스트에 다른 단언 사용 후보가 있다(검색 패턴이 넓어 정확한 건수는 단계 5에서 확정) | 단계 5 |
| 픽스처·`lateinit` | 공용 픽스처 `object`는 없다. 테스트 `@Autowired lateinit`은 없다 | 필요 시 단계 6 |
| 테스트 의존성 주입, 컨텍스트 재사용 | 이미 충족 | 없음 |

## 3. 단계

각 단계는 독립적으로 커밋할 수 있다. 순서대로 진행하고, 단계마다 `./scripts/agent/test/run-tests.sh`와 `ktlintCheck detekt` 통과를 확인한다.

### 단계 1. `hashCode` 정리 (`member`, `catalog`)

- [ ] 6개 엔티티의 `hashCode`를 `javaClass.hashCode()`로 바꾼다. 프록시에서 `javaClass`가 하위 클래스가 되어 `equals`와 어긋나는지 확인하고, 어긋나면 `Hibernate.getClass(this)`를 쓸지 판단한다 (`hibernate-core` 의존이 `domain`에 이미 있는지 확인)
- [ ] 영속 전후 `hashCode`가 같은지, `Set`에 담은 뒤 `save`해도 유지되는지 검증하는 단위 테스트를 `Member` 기준으로 하나 추가한다

완료 기준: 6개 엔티티에 `id?.hashCode()`가 남지 않는다. 기존 테스트 통과.

### 단계 2. `protected set` 적용 (`member`, `catalog`)

- [ ] 공개 `var` 약 30곳을 분류한다. (a) 변경 메서드가 이미 있어 `protected set`으로 막을 수 있는 것, (b) 서비스·매퍼·시드 러너가 직접 대입하는 것
- [ ] (b)가 있으면 의미 있는 이름의 변경 메서드를 추가하고 호출부를 옮긴다. 서비스 대입을 늘리는 방향으로 고치지 않는다
- [ ] 생성자 `var`가 공개 `set`으로 남는지 확인한다. 생성자 `var`에도 `protected set`을 붙일 수 없으면(문법상 불가) 본문 선언으로 옮기는 비용을 따져 보고한다
- [ ] `private set`이 컴파일되지 않는 것을 현재 Kotlin·allOpen 조합에서 한 번 확인한다 (지침 문장의 근거)

**사용자 확인 필요:** 생성자 파라미터로 선언한 `var`는 `protected set`을 쓸 수 없어 본문으로 옮겨야 한다. 옮기면 엔티티 선언이 길어지고 AGENTS.md의 "JPA 엔티티 애노테이션은 한 줄씩" 규칙과 겹쳐 diff가 커진다. 비용이 크면 대안은 "변경 메서드가 있는 속성만 적용"이다. 단계 2를 시작하기 전에 분류 결과와 함께 다시 묻는다.

완료 기준: `domain` 엔티티의 속성 변경이 변경 메서드를 통해서만 가능하다 (합의된 범위).

### 단계 3. 컬렉션 불변 노출

- [ ] `Venue.seats`를 `Collections.unmodifiableList(_seats)`로 감싼다. `catalog`의 다른 연관 컬렉션도 같은 패턴이 있는지 `rg`로 확인한다
- [ ] 캐스팅 후 수정이 `UnsupportedOperationException`인 회귀 테스트를 추가한다

완료 기준: 엔티티의 컬렉션 getter가 수정 가능한 컬렉션을 반환하지 않는다.

### 단계 4. 검증 애노테이션과 예외 매핑 확인 (코드 변경은 필요 시에만)

- [ ] `SignUpRequest`에 필수 값을 누락·빈 문자열로 보내 `@field:` 제약이 동작하고 `INVALID_REQUEST`(또는 필드 오류 형식)로 응답되는지 `MockMvc` 테스트로 확인한다. 이미 있으면 재사용한다
- [ ] 필수 non-null 필드를 JSON에서 누락했을 때 `HttpMessageNotReadableException`이 `ApiExceptionHandler`에서 어떻게 응답되는지 확인한다. 500이면 `code`가 있는 400으로 매핑하는 핸들러를 추가한다
- [ ] 결과가 [core API 지침](../../rules/backend-core.md#api)의 검증 애노테이션·불변식 오류 규칙과 맞는지 대조하고 어긋나면 지침을 고친다

완료 기준: 위 세 경로가 모두 4xx + 안정적 `code`로 응답된다.

### 단계 5. 단언 통일

- [ ] `rg`로 JUnit `Assertions`·`kotlin.test` 단언을 쓰는 파일을 정확히 센다 (모듈별: `core`, `realtime`, `gateway`, `fake-pg`)
- [ ] `fake-pg`가 AssertJ 의존성을 갖는지 `build.gradle.kts`로 확인한다. 없으면 의존성 추가가 필요하므로 별도 합의 후 진행한다 (**사용자 확인 필요 가능성**)
- [ ] 기계적으로 치환하고 의미가 바뀌는 단언(`assertEquals(expected, actual)` 인자 순서)에 주의한다

완료 기준: 합의된 모듈의 테스트가 AssertJ만 쓴다.

### 단계 6. 픽스처 (선택, 필요가 생길 때)

- [ ] 두 테스트 클래스 이상이 중복해서 쓰는 객체 생성 코드를 `rg`로 찾아 `object XxxFixture`로 옮긴다. 후보가 없으면 건너뛰고 이 단계를 닫는다
- [ ] 상태가 필요한 객체는 도메인 메서드로 만든다. 리플렉션 주입이 남은 곳은 `id` 용도인지 확인한다

완료 기준: 후보가 없다는 확인 결과를 기록하거나, 옮긴 뒤 기존 테스트가 통과한다.

### 단계 7. 마무리

- [ ] [core JPA 엔티티 지침](../../rules/backend-core.md#jpa-엔티티)을 현재 코드와 대조한다 (특히 `protected set` 예외 범위, `hashCode` 항목)
- [ ] `./scripts/agent/docs/check-docs.sh docs/rules docs/plans`로 링크·경로를 검사한다
- [ ] 이 문서의 체크박스와 상태를 갱신한다

## 4. 위험과 주의

- 단계 2는 변경 폭이 가장 크다. `member`·`catalog`의 API·시드 러너·테스트가 setter를 직접 쓰고 있으면 호출부 수정이 번진다. 분류 결과를 보고한 뒤 진행한다.
- `hashCode` 변경(단계 1)은 `Set`·`Map` 키로 엔티티를 쓰는 코드의 동작을 바꾼다. `rg`로 해당 사용처가 있는지 먼저 확인한다.
- 이 계획은 `docs/roadmap.md`의 단계(M5 대기)와 무관한 리팩터링이다. M5 항목보다 먼저 할지는 사용자가 정한다.
- 가이드의 "✅ 실제 확인" 표시는 다른 버전 조합의 결과다. 지침에 옮긴 규칙 중 `private set` 컴파일 오류와 `@field:` 동작은 단계 2·4에서 이 저장소 기준으로 다시 확인한다.
