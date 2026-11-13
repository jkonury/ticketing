# Step 1: metrics-tracing-core

- model: 기본 / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `build-logic/src/main/kotlin/ticketing.spring-conventions.gradle.kts` (모든 Spring 앱 공통 의존성. actuator가 이미 있다)
- `core/src/main/resources/application.yml`, `fake-pg/src/main/resources/application.yml`
- `core/src/main/kotlin/io/github/ticketing/member/security/SecurityConfig.kt` (actuator 허용 경로, 49행 부근)
- `core/src/test/kotlin/io/github/ticketing/IntegrationTestSupport.kt` (통합 테스트 기반)
- `docs/rules/backend.md § 테스트` (테스트 규칙)

## 배경

M5 부하 테스트에서 Prometheus가 지표를 수집하고 Tempo가 trace를 받는다(ADR 0024). 이 step은 공통 의존성과 MVC 앱(core, fake-pg) 설정을 맡는다. realtime·gateway 설정은 step 2에서 한다. Prometheus·Tempo 컨테이너는 step 3에서 만든다.

## 작업

라이브러리 이름, 자동 구성 조건, 속성 키는 기억에 의존하지 말고 **Spring Boot 4.1 공식 문서**(Actuator > Metrics, Tracing)와 Micrometer 문서로 확인한다. Boot 4에서 모듈과 속성 이름이 3.x와 달라진 것이 있다.

### 1. 공통 의존성: `ticketing.spring-conventions.gradle.kts`

- Prometheus registry를 추가한다.
- Micrometer Tracing의 OpenTelemetry 브리지와 OTLP(HTTP) span 내보내기를 추가한다. Boot 4.1에 이를 묶은 starter가 있으면 그것을 쓴다.
- Virtual Thread 지표(`VirtualThreadMetrics`, pinning 이벤트 수 등)를 제공하는 Micrometer 모듈을 확인해 추가한다. Boot 4.1이 자동 등록하지 않으면 core에 `MeterBinder` 빈으로 등록한다.
- 버전은 Boot BOM에 맡긴다. BOM이 관리하지 않으면 `gradle/libs.versions.toml`에 추가한다.

### 2. 설정: core·fake-pg `application.yml`

두 앱에 같은 규칙으로 넣는다.

- 노출 endpoint: `health`, `info`, `prometheus` (core는 기존 Modulith endpoint가 있으면 유지).
- `http.server.requests` 히스토그램(percentile histogram)을 켠다. k6 지표와 비교할 p95·p99를 Prometheus에서 계산하기 위해서다.
- 공통 태그 `application`=`${spring.application.name}`.
- 샘플링 비율: `${TRACING_SAMPLING_PROBABILITY:1.0}`.
- span 내보내기 활성화: `${TRACING_EXPORT_ENABLED:false}`. OTLP 엔드포인트: `${OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}`. 기본으로 꺼 두는 이유는 Tempo 없이 개발할 때 내보내기 실패 로그가 쌓이기 때문이다.
- 로그에 traceId·spanId가 찍히는지 확인한다. Boot 기본 상관 ID 패턴으로 충분하면 따로 설정하지 않는다.

### 3. core 보안과 비동기 전파

- `SecurityConfig`에서 `/actuator/prometheus`를 `permitAll`에 추가한다. Gateway는 `/api/v1/**`만 라우팅하므로 외부에 노출되지 않는다. 네트워크 차단은 M6에서 한다.
- Modulith `@ApplicationModuleListener`(비동기)로 넘어간 처리도 같은 trace에 이어지도록 Boot가 쓰는 비동기 실행기에 컨텍스트 전파(`ContextPropagatingTaskDecorator` 등 공식 문서가 권하는 방식)를 적용한다. 이미 자동 적용되면 그 근거(문서 절)를 결과 보고에 적고 코드를 추가하지 않는다.
- Hikari 지표(`hikaricp_connections_*`)는 자동 등록된다. 풀 크기 설정은 바꾸지 않는다(M5-2 대상).

### 4. 테스트

- core: `ActuatorPrometheusTests`(통합 테스트 기반 재사용). 인증 없이 `GET /actuator/prometheus`가 200이고 본문에 `hikaricp_connections`, `http_server_requests`, `application="core"`가 있다. API 요청을 한 번 보낸 뒤 확인한다.
- fake-pg: 같은 이름의 테스트로 200과 `application="fake-pg"`를 확인한다.
- 테스트에서 span 내보내기는 꺼진 기본값을 그대로 쓴다.

## Acceptance Criteria

```bash
rg -q 'prometheus' build-logic/src/main/kotlin/ticketing.spring-conventions.gradle.kts
rg -q 'TRACING_EXPORT_ENABLED' core/src/main/resources/application.yml
rg -q 'TRACING_EXPORT_ENABLED' fake-pg/src/main/resources/application.yml
rg -q '/actuator/prometheus' core/src/main/kotlin/io/github/ticketing/member/security/SecurityConfig.kt
./scripts/agent/test/run-tests.sh :core:test --tests "*ActuatorPrometheusTests"
./scripts/agent/test/run-tests.sh :fake-pg:test --tests "*ActuatorPrometheusTests"
./scripts/agent/test/check-summary.sh :core:ktlintCheck :core:detekt :fake-pg:ktlintCheck :fake-pg:detekt
```

## 검증 절차

1. 위 AC를 실행한다.
2. 의존성 추가가 realtime·gateway·core-batch 빌드를 깨지 않는지 `./scripts/agent/test/check-summary.sh :realtime:compileKotlin :gateway:compileKotlin :core-batch:compileKotlin`로 확인한다.
3. 마지막에 `./scripts/agent/test/run-tests.sh :core:test :fake-pg:test`로 회귀를 확인한다.

## 금지사항

- realtime·gateway의 `application.yml`을 고치지 마라. 이유: step 2 범위다. 공통 의존성 추가로 생긴 컴파일 오류만 보고한다.
- Hikari 풀 크기, Tomcat·Virtual Thread 설정을 바꾸지 마라. 이유: 기준 측정 전에 바꾸면 전후 비교가 무의미해진다.
- span 내보내기를 기본으로 켜지 마라. 이유: Tempo가 없는 개발·테스트 환경에서 내보내기 실패 로그가 계속 쌓인다.
- `/actuator/**` 전체를 `permitAll`로 열지 마라. 이유: `prometheus`와 기존 `health`만 필요하다.
