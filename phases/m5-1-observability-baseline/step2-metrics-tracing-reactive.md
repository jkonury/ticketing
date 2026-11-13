# Step 2: metrics-tracing-reactive

- model: 기본 / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `core/src/main/resources/application.yml` (step 1에서 추가한 `management.*` 설정. 같은 규칙을 따른다)
- `realtime/src/main/resources/application.yml`, `gateway/src/main/resources/application.yml`
- `gateway/src/test/kotlin/io/github/ticketing/gateway/GatewayIntegrationTestSupport.kt` (하위 서버 스텁 방식)
- `realtime/src/test/kotlin/io/github/ticketing/realtime/RealtimeIntegrationTestSupport.kt`
- `docs/rules/backend.md § WebFlux + Coroutines`, `§ 테스트`

## 배경

step 1에서 공통 의존성(Prometheus registry, Micrometer Tracing OTel 브리지, OTLP 내보내기)과 core·fake-pg 설정을 넣었다. 이 step은 WebFlux 앱(realtime, gateway)에 같은 설정을 적용하고, 리액티브 체인과 Coroutines에서 trace 컨텍스트가 끊기지 않게 한다. 목표 trace 모양은 `gateway → core` 또는 `gateway → realtime`이 하나의 traceId로 이어지는 것이다.

## 작업

속성 키와 컨텍스트 전파 방식은 **Spring Boot 4.1**, **Spring Cloud Gateway(2025.1.x) Observability**, **Micrometer Context Propagation** 공식 문서로 확인한다.

### 1. 설정: realtime·gateway `application.yml`

step 1의 core 설정과 같은 규칙을 쓴다: 노출 endpoint `health`·`info`·`prometheus`, `http.server.requests` percentile histogram, 공통 태그 `application`, `${TRACING_SAMPLING_PROBABILITY:1.0}`, `${TRACING_EXPORT_ENABLED:false}`, `${OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}`.

- gateway: 라우팅 지표(`spring.cloud.gateway.requests` 등)와 하위 요청에 `traceparent` 헤더 전파가 켜져 있는지 확인한다. 기본으로 꺼져 있으면 켠다.
- Reactor 자동 컨텍스트 전파(`spring.reactor.context-propagation=auto` 또는 공식 문서가 권하는 방식)를 두 앱에 적용한다.
- realtime: `suspend` 핸들러와 `Flow` 기반 SSE에서 로그에 traceId가 찍히는지 확인한다. 끊기면 공식 문서가 권하는 Coroutines 전파 방식(예: `PropagationContextElement`)을 적용한다.
- Redis(Lettuce) 명령 지표·span이 자동으로 생기는지 확인하고 결과를 보고한다. 별도 설정을 추가하지는 않는다.

### 2. 테스트

- gateway `ActuatorPrometheusTests`: `GET /actuator/prometheus`가 200이고 `application="gateway"`가 있다.
- gateway `TracePropagationTests`: 기존 하위 서버 스텁에 요청을 보내면 하위 서버가 받은 요청에 `traceparent` 헤더가 있고, 클라이언트가 보낸 `traceparent`의 traceId가 유지된다.
- realtime `ActuatorPrometheusTests`: 200과 `application="realtime"`.

## Acceptance Criteria

```bash
rg -q 'TRACING_EXPORT_ENABLED' realtime/src/main/resources/application.yml
rg -q 'TRACING_EXPORT_ENABLED' gateway/src/main/resources/application.yml
./scripts/agent/test/run-tests.sh :gateway:test --tests "*ActuatorPrometheusTests" --tests "*TracePropagationTests"
./scripts/agent/test/run-tests.sh :realtime:test --tests "*ActuatorPrometheusTests"
./scripts/agent/test/check-summary.sh :realtime:ktlintCheck :realtime:detekt :gateway:ktlintCheck :gateway:detekt
```

## 검증 절차

1. 위 AC를 실행한다.
2. 마지막에 `./scripts/agent/test/run-tests.sh :gateway:test :realtime:test`로 회귀를 확인한다. 기존 SSE·rate limit 테스트가 컨텍스트 전파 설정 때문에 깨지면 설정을 고친다. 테스트를 고쳐 맞추지 않는다.

## 금지사항

- `Hooks.enableAutomaticContextPropagation()`을 코드에서 직접 호출하지 마라(공식 문서가 속성 방식을 권하면 그것을 쓴다). 이유: 설정 위치가 둘로 나뉜다.
- realtime에서 블로킹 MDC 조작이나 `ThreadLocal` 직접 접근 코드를 추가하지 마라. 이유: WebFlux 규칙 위반이고, 이벤트 루프 스레드에서 값이 섞인다.
- Redis Streams(core → realtime 좌석 이벤트)에 trace 컨텍스트를 싣지 마라. 이유: 메시지 형식이 바뀌는 계약 변경이다. 필요하면 결과 보고에 후속 과제로 적는다.
- core·fake-pg 설정을 고치지 마라. 이유: step 1 범위다.
