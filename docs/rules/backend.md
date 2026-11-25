# 백엔드 공통 작성 지침

Kotlin, Spring Boot 4.x, Java 25 기반이다. 설계 근거는 [아키텍처](../architecture.md)를 참고한다.

## 작업 경로별 지침

공통 지침을 읽고 변경하는 경로의 지침을 확인한다. 여러 모듈에 걸친 변경이면 해당 모듈의 지침을 모두 읽는다.

| 작업 경로 | 문서 |
|---|---|
| `core/` | [core 작성 지침](backend-core.md) |
| `core-batch/` | [core-batch 작성 지침](backend-core-batch.md) |
| `payment/` | [payment 작성 지침](backend-payment.md) |
| `payment-batch/` | [payment-batch 작성 지침](backend-payment-batch.md) |
| `realtime/` | [realtime 작성 지침](backend-realtime.md) |
| `gateway/` | [gateway 작성 지침](backend-gateway.md) |
| `fake-pg/` | [fake-pg 작성 지침](backend-fake-pg.md) |
| `common/` | [common 작성 지침](backend-common.md) |
| `contract/` | [contract 작성 지침](backend-contract.md) |
| 백엔드 테스트, 테스트 픽스처, `e2e/`, 부하 테스트 | [백엔드 테스트 지침](backend-testing.md) |

리뷰는 [백엔드 리뷰 지침](backend-review.md)을 따른다.

## 빌드

- Gradle Kotlin DSL. 의존성 버전은 `gradle/libs.versions.toml`(version catalog)에만 적는다.
- 공통 빌드 설정은 convention plugin으로 모으고, 각 모듈의 `build.gradle.kts`에는 그 모듈 고유의 것만 둔다.
- 포맷은 ktlint, 정적 분석은 detekt(`build-logic`의 `ticketing.kotlin-quality`). `@Suppress`·baseline 규칙은 [AGENTS.md 6절](../../AGENTS.md#6-테스트와-검사)을 따른다.

## Kotlin

- `!!`를 쓰지 않는다. null은 타입과 `?:`, `requireNotNull`, `checkNotNull`로 다룬다.
- 도메인 모델은 `val` 우선. 상태 변경은 의미 있는 이름의 메서드로만 한다 (`booking.confirm()`), setter를 노출하지 않는다.
- 식별자는 `@JvmInline value class`로 감싼다 (`BookingId`, `SeatId`, `MemberId`). 서로 다른 ID를 `Long`으로 섞어 쓰지 않는다.
- 상태와 결과의 분기는 `sealed interface` / `enum` + `when`으로 표현하고 `else`를 두지 않는다.
- 확장 함수는 그것을 쓰는 모듈 안에 둔다. 전역 유틸 모듈을 만들지 않는다.

### 가독성

- 변수명에 약어를 쓰지 않는다. `b`, `p`, `a`, `c`, `x` 같은 이름 대신 상태나 역할이 드러나는 이름을 쓴다 (`pendingBooking`, `payment`, `firstLoaded`).
- `main` 소스는 논리 단위(선언 그룹, 검증, 저장, 발행, 로깅)마다 빈 줄로 구분한다. 한 줄에 `;`로 여러 문장을 잇지 않는다.
- 줄 단위 구분은 의미를 바꾸지 않는다. 검증 순서가 예외 메시지에 영향을 주면 기존 순서를 유지한다.

## Virtual Thread

`core`, `payment`, `fake-pg`에 적용한다.

- `spring.threads.virtual.enabled=true`.
- 블로킹 I/O를 감싸는 `synchronized` 대신 `ReentrantLock`을 쓴다. 라이브러리 pinning 여부는 부하 테스트 단계에서 확인한다.
- `ThreadLocal`에 큰 객체를 캐싱하지 않는다.
- 스레드 풀로 동시성을 제한하지 않는다. 제한이 필요하면 `Semaphore`나 커넥션 풀 크기로 한다.

## WebFlux

`realtime`과 `gateway`에 적용한다.

- **블로킹 호출 금지.** `runBlocking`, `Thread.sleep`, 블로킹 Redis/HTTP 클라이언트를 쓰지 않는다. Redis는 reactive 클라이언트를 쓴다.
- readiness 전 내부 예열을 수행하는 `ApplicationRunner`의 기동 스레드만 제한 시간 동안 Future 완료를 기다릴 수 있다([ADR 0029](../adr/0029-layered-startup-warmup.md)). 예열 HTTP·재시도·지연은 reactive 연산이며 요청 이벤트 루프에는 블로킹을 넣지 않는다.

## 로깅

- SLF4J + 파라미터 방식(`log.info("... {}", id)`). 문자열 연결로 로그를 만들지 않는다.
- 예외를 잡았으면 처리하거나 다시 던진다. 로그만 남기고 삼키지 않는다.
- 비밀번호, 토큰, 결제 정보를 로그에 남기지 않는다.
