# 아키텍처

구현 순서와 진행 상태는 [roadmap.md](./roadmap.md), 결정별 대안과 선택 이유는 [adr/](./adr/README.md)를 본다.

## 문서 구성

이 문서는 전체 그림(§1–§4, §11)을 담고, 구현 상세는 아래 문서로 나눈다. 절 번호는 분리 전과 같다.

| 문서 | 절 | 내용 |
|---|---|---|
| [architecture/erd.md](./architecture/erd.md) | §5 | PostgreSQL 스키마 규칙, ERD, JPA·jOOQ 역할 분담 |
| [architecture/redis.md](./architecture/redis.md) | §5 | Redis 용도, 대기열·좌석 선점 키 형식 |
| [architecture/flows.md](./architecture/flows.md) | §6 | 대기열·입장, 좌석 선점, 예매 시퀀스, 상태 전이, 결제 |
| [architecture/integrations.md](./architecture/integrations.md) | §7, §8, §10 | 인증·인가, BFF, 외부 연동(fake-pg 계약) |
| [architecture/batch.md](./architecture/batch.md) | §9 | Batch Job과 정산·대사 규칙 |
| [architecture/events.md](./architecture/events.md) | §4 | 이벤트 카탈로그: 도메인 이벤트, 좌석 Stream, SSE 이벤트 |
| [architecture/data-ownership.md](./architecture/data-ownership.md) | §5 | 데이터 소유: 스키마·Redis 키 소유자와 접근 방법 |
| [architecture/api-conventions.md](./architecture/api-conventions.md) | §7, §8 | API 규약: 인증, 헤더, 오류 형식, 페이지네이션, API 목록 |

## 1. 프로젝트 개요

| 항목 | 내용 |
|---|---|
| 목적 | 학습 겸 포트폴리오. 스택의 각 요소가 실제 필요에 의해 쓰이는 것이 기준 |
| 인원·기한 | 1인, 기한 없음 |
| 도메인 | 공연/티켓 예매 (지정석) |
| 저장소 | 모노레포 `ticketing` |
| 기본 패키지 | `io.github.ticketing` |
| 장기 계획 | Modulith → MSA + k8s |

도메인 선정 이유: 대기열, 좌석 선점, 결제, 정산이 모듈 경계로 나뉘고, 트랜잭션 중심 코어(MVC)와 실시간 팬아웃(WebFlux+SSE)이 자연스럽게 분리된다.

## 2. 기술 스택

| 영역 | 선택 |
|---|---|
| 언어 | 전부 Kotlin (JPA용 `allopen`, `noarg` 플러그인 사용) |
| 런타임 | Java 25 (LTS) |
| 프레임워크 | Spring Boot 4.x 최신 안정판 |
| 빌드 | Gradle Kotlin DSL, 멀티 모듈 |
| 코어 웹 | Spring WebMVC + Virtual Thread |
| 실시간 웹 | Spring WebFlux + Coroutines + SSE |
| Gateway | Spring Cloud Gateway (WebFlux) |
| 모듈화 | Spring Modulith |
| 영속성 | JPA(쓰기) + jOOQ(조회·집계), Flyway |
| DB | PostgreSQL |
| 캐시·메시징 | Redis (캐시, 좌석 선점, 대기열, Streams) |
| 배치 | Spring Batch |
| 보안 | Spring Security, JWT |
| 프런트 | Next.js 최신 안정판 (App Router) + TypeScript, BFF 역할 |

### 확정 버전

| 항목 | 버전 | 비고 |
|---|---|---|
| Java | 25 (Temurin) | toolchain으로 고정 |
| Kotlin | 2.3.21 | |
| Spring Boot | 4.1.2 | |
| Spring Modulith | 2.1.1 | BOM으로 관리 |
| Spring Cloud | 2025.1.4 | Gateway는 `spring-cloud-starter-gateway-server-webflux` |
| jOOQ | 3.21.9 | Boot BOM 관리. 코드 생성은 Testcontainers + Flyway 기반 |
| Gradle | 9.8.2 | wrapper |
| PostgreSQL / Redis | 18.3-alpine / 8.6.2-alpine | Compose, Testcontainers 공통 |
| Next.js | 16.4.6 | App Router |
| Node.js | 24 | web 런타임 |

버전은 `gradle/libs.versions.toml`이 기준이다. Boot BOM이 관리하는 라이브러리(jOOQ, Flyway, Testcontainers)는 BOM을 따른다.

### 빌드 구조 메모

- 공통 빌드 설정은 `build-logic/`의 convention plugin(`ticketing.kotlin-conventions`, `ticketing.spring-conventions`, `ticketing.jpa-conventions`)에 둔다.
- jOOQ 생성 코드의 패키지는 `ticketing.jooq`다. Modulith는 `io.github.ticketing`의 직계 하위 패키지를 모두 모듈로 보므로 루트 밖에 둔다.
- Modulith 모듈 선언은 Kotlin에 `package-info`가 없어 `core/src/main/java/.../<모듈>/package-info.java`에 둔다 (Java 파일은 이것뿐).
- Modulith 이벤트 발행 레지스트리 테이블(`event_publication`)은 `public` 스키마에 둔다.

## 3. 시스템 구성

```
브라우저
  ├─ 일반 요청 ──▶ Next.js BFF ──▶ Gateway ──▶ 코어 (Modulith, MVC+VT)
  └─ SSE(단기 티켓) ───────────▶ Gateway ──▶ 실시간 앱 (WebFlux+Coroutines)

코어 ──▶ PostgreSQL (모듈별 스키마)
코어 ──▶ Redis (좌석 선점, 좌석 스냅샷, Streams 발행)
실시간 앱 ──▶ Redis (대기열, Streams 구독)   ※ PostgreSQL 접근 없음
코어 ──▶ 가짜 PG 서버 (결제 요청) / 가짜 PG ──▶ 코어 (웹훅)
```

### 배포 단위

| 단위 | 루트 패키지 | 역할 |
|---|---|---|
| `core` | `io.github.ticketing` | 도메인 로직 전체. Modulith |
| `core-batch` | `io.github.ticketing` (core 코드 공유) | Spring Batch 실행 모듈. 추후 k8s CronJob |
| `realtime` | `io.github.ticketing.realtime` | 대기열, SSE push. 상태 없음(Redis만 사용) |
| `gateway` | `io.github.ticketing.gateway` | 라우팅, JWT·입장 토큰·SSE 티켓 검증, rate limit |
| `fake-pg` | `io.github.ticketing.fakepg` | 지연·실패·중복 웹훅을 재현하는 가짜 PG |
| `web` | - | Next.js BFF + 화면 |

한 JVM 앱에는 웹 스택이 하나만 올라가므로 MVC와 WebFlux는 배포 단위로 분리한다.

## 4. 코어 모듈 (Modulith)

Spring Modulith는 메인 클래스 패키지의 직계 하위 패키지를 모듈로 인식한다. 따라서 `io.github.ticketing.<모듈명>` 구조가 곧 모듈 경계다.

| 모듈 | 책임 | 구조 |
|---|---|---|
| `member` | 회원 가입, 로그인, JWT 발급, 역할 | 계층형 |
| `catalog` | 공연, 회차, 공연장, 좌석 배치 | 계층형 |
| `booking` | 좌석 선점, 예매 생성·확정·만료·취소 | 헥사고날 |
| `payment` | 결제 요청, 웹훅 처리, 환불, 결제 대사 (Batch) | 헥사고날 |
| `settlement` | 정산 (Batch). M2에서 구현 | 헥사고날 |
| `notification` | 알림 (초기에는 로그 출력). M1-6에서 구현 | 계층형 |

대기열(`queue`)은 코어가 아니라 `realtime` 앱 소유다. 코어는 대기열의 존재를 모른다.

### 모듈 규칙

- 모듈 간 호출은 **Modulith 이벤트**(`ApplicationModuleListener` + 이벤트 발행 레지스트리)로 한다.
- 다른 모듈의 데이터는 ID 참조와 이벤트로만 접근한다.
- 경계와 의존 방향은 Modulith 검증 테스트(`ApplicationModules.verify()`)와 ArchUnit으로 강제한다.
- MSA 분리 1순위는 `payment`, `settlement`.
- **결제 계약 이벤트의 소유**: `PaymentRequested`, `RefundRequested`, `PaymentSucceeded`, `PaymentFailed`, `PaymentRefunded`는 모두 `payment` 모듈 루트에 둔다. 의존 방향은 `booking → payment` 하나다. `payment`는 예매를 모르고 `orderId`만 안다. 양쪽이 각자 이벤트를 소유하면 순환이 생겨 `ApplicationModules.verify()`가 실패한다. [ADR 0017](./adr/0017-payment-owns-payment-events.md) 참고.

### 헥사고날 모듈의 패키지 구조

Kotlin 키워드와 겹치지 않도록 입력 측 패키지는 `provided`, 출력 측 패키지는 `required`로 명명한다. [ADR 0016](./adr/0016-provided-required-packages.md) 참고.

```
io.github.ticketing.booking
├── domain            순수 도메인 모델, 프레임워크 의존 없음
├── application
│   ├── port.provided 유스케이스 인터페이스
│   ├── port.required CommandPort(쓰기) / QueryPort(조회) 분리
│   └── service       유스케이스 구현
└── adapter
    ├── provided.web  컨트롤러
    ├── provided.event 이벤트 리스너
    └── required
        ├── jpa       CommandPort 구현
        ├── jooq      QueryPort 구현
        └── redis     좌석 선점 등
```

### 모듈 의존 다이어그램

Modulith `Documenter`가 생성한다(`DocumentationTests`, 출력 `docs/modulith/`). 모듈 의존을 바꾸면 테스트를 다시 실행해 바뀐 `.puml`을 함께 커밋한다.

- [전체 모듈 의존](./modulith/components.puml)
- 모듈별: [booking](./modulith/module-booking.puml), [catalog](./modulith/module-catalog.puml), [payment](./modulith/module-payment.puml), [member](./modulith/module-member.puml), [settlement](./modulith/module-settlement.puml), [notification](./modulith/module-notification.puml)

의존 방향은 `booking → catalog`(회차 사본 수신), `booking → payment`(결제 계약 이벤트), `notification → booking`(예매 알림 이벤트), `settlement → booking`(정산 사본 이벤트)이다.

### 이벤트 목록

이벤트 클래스는 발행 모듈의 루트 패키지에 둔다. 금액은 `BigDecimal`(원 단위), 시각은 `Instant`다.

| 이벤트 | 발행 모듈 | 구독 모듈 | 페이로드 |
|---|---|---|---|
| `ScheduleRegistered` | catalog | booking | `scheduleId`, `performanceId`, `performanceTitle`, `organizerId`, `startsAt`, `seats`(`seatId`, `grade`, `label`, `price`) |
| `PaymentRequested` | booking | payment | `orderId`(=예매 ID), `memberId`, `amount`, `idempotencyKey`, `requestedAt` |
| `RefundRequested` | booking | payment | `orderId`, `reason`, `requestedAt` |
| `PaymentSucceeded` | payment | booking | `paymentId`, `orderId`, `amount`, `paidAt` |
| `PaymentFailed` | payment | booking | `paymentId`, `orderId`, `reason`, `failedAt` |
| `PaymentRefunded` | payment | booking | `paymentId`, `orderId`, `refundedAt` |
| `BookingConfirmed` | booking | notification, settlement | `bookingId`, `memberId`, `scheduleId`, `performanceId`, `organizerId`, `seatIds`, `amount`, `confirmedAt` |
| `BookingCancelled` | booking | notification, settlement | `bookingId`, `memberId`, `scheduleId`, `performanceId`, `organizerId`, `reason`, `refundRequired`, `cancelledAt` |
| `BookingExpired` | booking | 없음(notification) | `bookingId`, `memberId`, `scheduleId`, `expiredAt` |

- 결제 계약 이벤트 5종(`PaymentRequested`, `RefundRequested`, `PaymentSucceeded`, `PaymentFailed`, `PaymentRefunded`)은 `payment` 모듈이 소유한다. 발행자는 위 표와 같지만 클래스는 `payment` 루트 패키지에 있다.
- 구독자는 `adapter.provided.event`의 `@ApplicationModuleListener`다. booking은 `ScheduleRegisteredListener`, `PaymentResultListener`, payment는 `PaymentRequestListener`, `RefundRequestListener`다. `PaymentRefunded`는 booking이 로그만 남긴다. notification은 `BookingNotificationListener`로 확정·취소를 로그에 남긴다. 로그는 재전달 시 중복될 수 있으며 실제 알림 발송 시 영속적인 중복 방지가 필요하다.

## 5. 데이터

- PostgreSQL은 인스턴스 하나에 모듈별 스키마(`member`, `catalog`, `booking`, `payment`, `settlement`, `batch`)를 두고 모듈 간 외래 키·조인을 금지한다. 스키마 규칙, ERD, JPA·jOOQ 역할 분담은 [erd.md](./architecture/erd.md)를 본다.
- booking의 불변 좌석 사본은 요청 좌석 단위 Redis 캐시로 읽고 누락·장애 시 DB에서 조회한다. 캐시는 커밋 후 채운다([ADR 0025](./adr/0025-booking-seat-copy-cache.md)).
- Redis는 좌석 선점·스냅샷·Streams, 대기열, 입장 토큰, SSE 티켓, rate limit, 조회 캐시에 쓴다. 용도와 키 형식은 [redis.md](./architecture/redis.md)를 본다.

## 6. 핵심 흐름

상세는 [flows.md](./architecture/flows.md)를 본다.

- [6.1 대기열과 입장](./architecture/flows.md#61-대기열과-입장): 대기열 등록, 입장 토큰, Gateway·realtime 규칙
- [6.2 좌석 선점](./architecture/flows.md#62-좌석-선점): 1회 최대 4매, TTL 5분, Lua 전부 성공 또는 전부 실패
- [6.2.1 예매 흐름 시퀀스](./architecture/flows.md#621-예매-흐름-시퀀스-대기열-제외)
- [6.3 예매 상태 전이](./architecture/flows.md#63-예매-상태-전이)
- [6.4 결제(이벤트 기반)](./architecture/flows.md#64-결제-이벤트-기반): 가짜 PG, 자동 환불 조건, 트랜잭션 구조

## 7. 인증·인가

`member` 모듈이 JWT를 발급하고 BFF가 토큰을 보관·갱신한다. SSE는 BFF를 거치지 않고 단기 SSE 티켓으로 Gateway에 직접 연결한다. 상세는 [integrations.md](./architecture/integrations.md)를 본다.

## 8. BFF (Next.js)

일반 API 요청은 모두 BFF를 거치며 조합 화면(공연 상세, 마이페이지, 주최자 대시보드)을 만든다. 상세는 [integrations.md](./architecture/integrations.md)를 본다.

## 9. Batch

Job은 `dailySettlementJob`, `expirePendingBookingsJob`, `reconcileSeatSnapshotJob`, `paymentReconciliationJob` 네 개다. 파라미터와 규칙은 [batch.md](./architecture/batch.md)를 본다.

## 10. 외부 연동

결제는 직접 만든 가짜 PG 서버로 대체하고 출력 포트로 추상화한다. 알림은 로그 출력으로 시작한다. fake-pg 연동 계약은 [integrations.md](./architecture/integrations.md)를 본다.

## 11. 개발 환경과 테스트

- 로컬 실행: Docker Compose (PostgreSQL, Redis, 가짜 PG)
- 통합 테스트: Testcontainers
- 모듈 테스트: Modulith 모듈 단위 테스트(`@ApplicationModuleTest`)
- 동시성 테스트: 같은 좌석에 대한 동시 선점·확정
- E2E: `e2e` 모듈과 `scripts/e2e/run.sh`가 Compose 스택과 core·gateway·realtime 두 프로세스를 띄워 대기열 → 입장 → 예매 확정 → 다른 사용자 좌석 SSE 도달 및 입장 토큰 차단을 검증한다. `./scripts/e2e/run.sh --web`은 여기에 web(Playwright)을 추가해 전체 예매 시나리오와 JWT 비노출을 검사한다. `build`·`check`에는 포함되지 않는다.
- 다중 인스턴스: realtime 통합 테스트는 같은 Redis를 사용하는 컨텍스트 두 개에서 순번·입장 락·좌석 push·`Last-Event-ID` 재연결을 검증한다. E2E에서는 Gateway가 realtime 한 인스턴스를 가리키고 두 번째에는 직접 연결한다. 부하 분산은 M6의 k8s Service에서 한다.
- 부하 테스트: k6(xk6-sse 빌드)가 Gateway(18000)를 직접 호출한다. 시나리오는 `queue-surge`(1만 명이 대기열 등록부터 예매 확정까지)와 `hot-seats`(1,000명이 앞줄 100석을 동시 선점)이고, `scripts/loadtest/run.sh --scenario <이름>`으로 실행한다. 결과는 `build/loadtest/runs/`에 저장하고 git에 넣지 않는다. 기준 측정 결과는 [m5-baseline.md](performance/m5-baseline.md)에 있다. [ADR 0024](adr/0024-observability-and-load-test.md) 참고.
- 관측: Micrometer + Prometheus 지표(`/actuator/prometheus`), Micrometer Tracing(OTel 브리지) → OTLP(HTTP) → Tempo 분산 추적, Grafana에서 지표와 trace를 연결한다. `compose.observability.yaml`(Prometheus, Tempo, Grafana)로 띄우며 Grafana는 13001 포트를 쓴다(13000은 web). span 내보내기는 `TRACING_EXPORT_ENABLED`(기본 `false`)와 `TRACING_SAMPLING_PROBABILITY`로 켜고 조절한다. [ADR 0024](adr/0024-observability-and-load-test.md) 참고.
- Gatling 후속 측정의 [전용 Grafana 대시보드와 스크립트 지표](./performance/gatling-hot-seats-dashboard.md)는 기존 Overview와 별도로 프로비저닝한다. 관측 스택의 읽기 전용 exporter가 러너의 수치 지표 파일을 Prometheus에 노출한다. 앱의 메트릭 태그에 요청 ID나 실행 ID를 추가하지 않는다.
- APM(선택): Scouter 에이전트를 `SCOUTER_AGENT_DIR`로 연결한다. WebFlux 앱(gateway·realtime)은 txid 전파를 Reactor 자동 컨텍스트 전파에 맡기는 패치 에이전트를 쓴다. [ADR 0027](adr/0027-scouter-reactive-txid-context-propagation.md) 참고.
- 성능 측정의 콜드: 앱을 새로 기동하고 외부 워밍업 요청을 보내지 않는 상태다. readiness 전 앱 내부 예열은 허용하되 별도 계열로 기록한다. [ADR 0028](adr/0028-cold-definition-internal-warmup.md) 참고.

- 선택 가능한 내부 예열([ADR 0029](./adr/0029-layered-startup-warmup.md), [호출량 확대 ADR 0030](./adr/0030-startup-warmup-burst-volume.md)): core·gateway는 기본 비활성이며, 활성화하면 readiness 전 메모리 연산과 격리된 실제 예매 호출을 수행한다. core 직접 호출은 200회·기본 상한 30초, Gateway 실제 HTTP 경유는 15회 × 1,000건·동시 요청 상한 1,000·기본 전체 상한 120초다. 음수 회차·`booking:warmup:` 키와 DB 롤백으로 일반 예매·SSE와 격리한다. HTTP 예열은 실제 Gateway 라우트·`BookingController`·`hasRole('USER')` 인가를 거치며, core가 기동 시 만든 일회성 비밀 자격·전용 보안 체인·Gateway 헤더 제거로 예열 principal을 보호한다([ADR 0031](./adr/0031-warmup-through-real-booking-handler.md)). 러너·E2E는 readiness를 기다린다.
