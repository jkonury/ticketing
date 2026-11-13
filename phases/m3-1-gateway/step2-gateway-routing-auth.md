# Step 2: gateway-routing-auth

- model: 기본 / reasoning_effort: high

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `gateway/build.gradle.kts`, `gateway/src/main/resources/application.yml`
- `gateway/src/main/kotlin/io/github/ticketing/gateway/GatewayApplication.kt`, `gateway/src/test/kotlin/io/github/ticketing/gateway/GatewayApplicationTests.kt`
- `core/src/main/kotlin/io/github/ticketing/member/security/SecurityConfig.kt` (HS256 디코더 구성, 오류 응답 본문 형식)
- `core/src/main/resources/application.yml` (`.env` import 방식)
- `core/src/test/kotlin/io/github/ticketing/TestcontainersConfiguration.kt` (Redis 컨테이너 재사용 방식 참고)
- `docs/rules/backend.md § WebFlux + Coroutines`, `§ Gateway`, `§ 테스트`
- `docs/architecture.md § 6.1 대기열과 입장` (step 0에서 갱신됨)

## 작업

Gateway의 빌드, 라우팅, JWT 검증, 오류 응답, 테스트 기반을 만든다. 입장 토큰·SSE 티켓·rate limit 필터는 다음 step에서 만든다.

**Spring Cloud 2025.1.4(`spring-cloud-starter-gateway-server-webflux`) 기준이다. Gateway 4.x에서 설정 키 접두사(`spring.cloud.gateway.server.webflux.*`)와 일부 API가 바뀌었으므로 기억이 아니라 공식 문서(docs.spring.io/spring-cloud-gateway 레퍼런스의 Server WebFlux 절)로 확인하라.**

### 1. 의존성 (`gateway/build.gradle.kts`)

- JWT 디코딩용 `org.springframework.security:spring-security-oauth2-jose`(버전은 Boot BOM). `NimbusReactiveJwtDecoder`만 쓴다.
- 테스트: `spring-boot-starter-webflux-test`(WebTestClient), `spring-boot-testcontainers`, `org.testcontainers:testcontainers-junit-jupiter`. 버전은 BOM에 맡긴다.

### 2. 설정

- `application.yml`: 코어와 같은 방식으로 `.env`를 import한다. `ticketing.jwt.secret`을 `TICKETING_JWT_SECRET`에서 받는다. 코어가 이 값을 어떻게 바인딩하는지 먼저 확인하고(`rg -n 'TICKETING_JWT_SECRET|ticketing.jwt' core/src/main`), 같은 `.env` 값이 두 앱에 들어가게 한다.
- `ticketing.gateway.core-uri`(기본 `http://localhost:8080`), `ticketing.gateway.realtime-uri`(기본 `http://localhost:8081`).
- 설정은 `@ConfigurationProperties` 클래스로 받는다. secret은 32바이트 이상인지 `init`에서 검사한다(코어 `JwtProperties`와 같은 규칙).

### 3. 라우팅: `gateway/src/main/kotlin/io/github/ticketing/gateway/route/GatewayRoutes.kt`

`RouteLocator` 빈을 Kotlin DSL로 정의한다. 다음 step이 경로별 필터를 붙일 수 있도록 라우트 ID를 고정한다.

| 라우트 ID | 조건 | 대상 | 필터 |
|---|---|---|---|
| `realtime-queues` | `/api/v1/queues/**` | realtime | 회원 필수, `X-Member-Id` 추가 |
| `realtime-streams` | `/api/v1/streams/**` | realtime | (step 4에서 SSE 티켓) |
| `core-api` | `/api/v1/**` | core | 없음 |

- realtime 라우트를 `core-api`보다 먼저 평가되게 순서를 지정한다.
- 경로는 바꾸지 않고 그대로 전달한다.

### 4. JWT 전역 필터: `gateway/src/main/kotlin/io/github/ticketing/gateway/auth/`

```kotlin
class JwtAuthenticationFilter(decoder: ReactiveJwtDecoder) : GlobalFilter, Ordered
object GatewayAttributes { const val MEMBER_ID: String }   // exchange attribute 이름
```

- 모든 요청에서 클라이언트가 보낸 `X-Member-Id` 헤더를 지운다(위조 방지).
- `Authorization: Bearer <token>`이 있으면 HS256으로 검증한다. 성공하면 `sub`(회원 ID, Long)를 exchange attribute에 넣고, 실패(서명·만료·형식)하면 401 `UNAUTHORIZED`로 응답하고 하위로 보내지 않는다.
- Bearer가 없으면 그대로 통과시킨다. core 경로의 인증 요구는 코어가 판단한다.
- `Authorization` 헤더는 지우지 않고 core로 그대로 전달한다(코어가 다시 검증한다).
- 이 필터는 라우팅 필터와 이후 step의 필터보다 먼저 실행되도록 순서를 정한다.

회원 필수 필터(`realtime-queues`): attribute에 회원 ID가 없으면 401 `UNAUTHORIZED`, 있으면 하위 요청에 `X-Member-Id`를 붙인다. 다음 step의 입장 토큰 필터도 회원 ID를 이 attribute에서 읽는다.

### 5. 오류 응답: `gateway/src/main/kotlin/io/github/ticketing/gateway/support/ProblemResponses.kt`

```kotlin
suspend fun ServerWebExchange.writeProblem(status: HttpStatus, code: String, detail: String)  // 또는 Mono 반환 함수
```

- `Content-Type: application/problem+json`, 본문은 코어 필터 오류와 같은 형태 `{"status":..,"detail":..,"code":..}`.
- 이후 step의 모든 거부 응답이 이 함수를 쓴다.

### 6. 테스트 기반

- `gateway/src/test/resources/junit-platform.properties`: `spring.test.constructor.autowire.mode=all`.
- `gateway/src/test/kotlin/io/github/ticketing/gateway/GatewayIntegrationTestSupport.kt`: `@SpringBootTest(webEnvironment = RANDOM_PORT)`, WebTestClient, Redis 컨테이너(`redis:8.6.2-alpine`, JVM 단위로 한 번만 시작), 테스트용 `ticketing.jwt.secret`, 하위 서비스 스텁의 URI를 `DynamicPropertyRegistrar`(또는 `@DynamicPropertySource`)로 등록한다. 이후 step의 테스트는 모두 이 클래스를 상속해 한 컨텍스트를 공유한다.
- 하위 서비스 스텁 `DownstreamStub`: 새 의존성 없이 reactor-netty `HttpServer`로 임의 포트에 core용·realtime용 두 개를 띄운다. 받은 요청(메서드, 경로, 쿼리, 헤더, 본문)을 기록하고 200으로 응답한다. 테스트마다 기록을 비울 수 있게 한다.
- 테스트용 JWT는 `NimbusJwtEncoder`와 같은 secret으로 만든다(만료된 토큰, 다른 secret 토큰 포함). 헬퍼는 support에 둔다.
- `GatewayApplicationTests`는 support를 상속하게 고친다(삭제하지 않는다).

### 7. 테스트: `gateway/src/test/kotlin/io/github/ticketing/gateway/route/GatewayRoutingTests.kt`

- `/api/v1/bookings/me`는 core 스텁에, `/api/v1/queues/1/entries`는 realtime 스텁에 도달한다.
- 클라이언트가 보낸 `X-Member-Id`는 core·realtime 어디에도 전달되지 않는다.
- 유효한 JWT로 `/api/v1/queues/**` 호출 시 realtime이 `X-Member-Id: <sub>`를 받는다. JWT가 없으면 401 `UNAUTHORIZED`이고 스텁에 도달하지 않는다.
- 서명이 틀리거나 만료된 JWT는 경로와 관계없이 401 `UNAUTHORIZED`.
- JWT 없이 core 경로 호출은 그대로 전달된다. 유효한 JWT의 `Authorization` 헤더는 core에 그대로 도달한다.

## Acceptance Criteria

```bash
rg -q 'spring-security-oauth2-jose' gateway/build.gradle.kts
! rg -q 'spring-boot-starter-security|jpa|jdbc|jooq' gateway/build.gradle.kts
test -f gateway/src/main/kotlin/io/github/ticketing/gateway/route/GatewayRoutes.kt
test -f gateway/src/test/kotlin/io/github/ticketing/gateway/GatewayIntegrationTestSupport.kt
./scripts/agent/test/run-tests.sh :gateway:test
./scripts/agent/test/check-summary.sh :gateway:ktlintCheck :gateway:detekt
```

## 검증 절차

1. `run-tests.sh :gateway:test --tests "*GatewayRoutingTests"`부터 통과시킨 뒤 위 AC 전체를 실행한다.
2. 로컬에서 `.env`가 있으면 `./gradlew :gateway:bootRun`으로 8000 포트에 뜨는지 한 번 확인한다(AC는 아님). 결과를 완료 보고에 적는다.

## 금지사항

- `spring-boot-starter-security`나 `spring-boot-starter-security-oauth2-resource-server`를 추가하지 마라. 이유: 기본 보안 체인이 모든 경로에 인증을 걸고, Gateway는 경로별 필터로만 검증한다는 설계와 충돌한다.
- core 경로에서 JWT가 없다는 이유로 요청을 막지 마라. 이유: 공개 API(가입, 로그인, 공연 조회)가 있고 인가는 코어가 강제한다.
- 필터에서 블로킹 호출(`block()`, `runBlocking`)을 쓰지 마라. 이유: `docs/rules/backend.md § WebFlux + Coroutines`.
- JPA, JDBC, jOOQ 의존성을 추가하지 마라. 이유: Gateway는 PostgreSQL에 접근하지 않는다.
- 이 step에서 입장 토큰·SSE 티켓·rate limit 필터를 만들지 마라. 이유: step 3~5의 범위다.
- 토큰 값을 로그에 남기지 마라.
- 기존 테스트를 깨뜨리지 마라.
