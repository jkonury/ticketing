# 티켓 예매 서비스 설계 합의서

- 합의일: 2026-11-10
- 상태: 설계 합의 완료, 구현 미착수
- 구현 순서는 [roadmap.md](./roadmap.md) 참고

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

### 확정 버전 (2026-11-10, M0 기준)

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
| `gateway` | `io.github.ticketing.gateway` | 라우팅, 입장 토큰 검증, SSE 티켓 검증, rate limit |
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
| `payment` | 결제 요청, 웹훅 처리, 환불 | 헥사고날 |
| `settlement` | 정산, 결제 대사 (Batch) | 헥사고날 |
| `notification` | 알림 (초기에는 로그 출력) | 계층형 |

대기열(`queue`)은 코어가 아니라 `realtime` 앱 소유다. 코어는 대기열의 존재를 모른다.

### 모듈 규칙

- 모듈 간 호출은 **Modulith 이벤트**(`ApplicationModuleListener` + 이벤트 발행 레지스트리)로 한다.
- 다른 모듈의 데이터는 ID 참조와 이벤트로만 접근한다.
- 경계와 의존 방향은 Modulith 검증 테스트(`ApplicationModules.verify()`)와 ArchUnit으로 강제한다.
- MSA 분리 1순위는 `payment`, `settlement`.

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

## 5. 데이터

### PostgreSQL

- 인스턴스 하나, **모듈별 스키마 분리**: `member`, `catalog`, `booking`, `payment`, `settlement`
- **모듈 간 외래 키·조인 금지**
- `settlement`의 집계에 필요한 타 모듈 데이터는 이벤트로 받아 자기 스키마에 사본으로 쌓는다.
- 스키마 관리는 Flyway, jOOQ 코드는 Flyway가 만든 스키마에서 생성한다.

### JPA와 jOOQ 역할 분담

- 명령(쓰기): JPA
- 조회(목록, 통계, 정산 집계): jOOQ
- 출력 포트를 `CommandPort` / `QueryPort`로 나누고 어댑터를 각각 구현한다.

### Redis 용도

| 용도 | 자료구조 | 소유 |
|---|---|---|
| 좌석 임시 선점 | `SET NX` + TTL, 다중 좌석은 Lua | 코어 `booking` |
| 좌석 현황 스냅샷 | Hash 등 (회차별) | 코어가 유지, 실시간 앱이 읽음 |
| 좌석 상태 변경 전달 | Streams | 코어 발행, 실시간 앱 구독 |
| 대기열 | Sorted Set | 실시간 앱 |
| 입장 토큰 | 단기 키 | 실시간 앱 발급, Gateway 검증 |
| 조회 캐시 | 공연·회차 정보 | 코어 `catalog` |

## 6. 핵심 흐름

### 6.1 대기열과 입장

1. 사용자가 예매 페이지 진입 시 실시간 앱의 대기열(Sorted Set)에 등록된다.
2. 실시간 앱이 순번을 SSE로 push한다.
3. 차례가 되면 **짧은 수명의 입장 토큰**을 발급한다.
4. Gateway가 예매 API 요청에서 입장 토큰을 검증한다. 코어는 관여하지 않는다.

### 6.2 좌석 선점

- 1회 예매 최대 **4매**
- 선점 유지 시간(TTL) **5분**
- 여러 좌석은 Lua 스크립트로 **전부 성공하거나 전부 실패**
- 최종 확정은 결제 성공 시 DB 기록. `(회차, 좌석)` **유니크 제약**이 마지막 방어선
- 선점·해제·확정 시 좌석 상태 변경을 Redis Streams로 발행하고 스냅샷을 갱신한다.

### 6.3 예매 상태 전이

```
PENDING ──결제 성공──▶ CONFIRMED ──사용자 취소·환불──▶ CANCELLED
   │
   ├──시간 초과──▶ EXPIRED
   └──사용자 취소──▶ CANCELLED
```

### 6.4 결제 (이벤트 기반)

1. `booking`이 결제 요청 이벤트 발행
2. `payment`가 가짜 PG에 결제 요청 (멱등 키 포함)
3. 가짜 PG가 웹훅으로 결과 통지 (지연, 실패, 중복 가능)
4. `payment`가 멱등 키로 중복을 걸러내고 결과 이벤트 발행
5. `booking`이 `CONFIRMED` 또는 실패 처리(좌석 반환)

**만료 후 결제 성공**: 결제는 성공했는데 선점이 이미 만료된 경우 자동 환불한다.

이 구조는 MSA 전환 시 Saga로 그대로 이어진다.

## 7. 인증·인가

- `member` 모듈이 JWT(access/refresh)를 직접 발급한다.
- **BFF가 토큰을 보관·갱신**한다. 브라우저에는 httpOnly 쿠키 세션만 내려가고 토큰은 노출되지 않는다.
- 역할: **일반 사용자**(예매), **주최자**(공연·회차 등록, 정산 조회). 역할 기반 인가 적용.
- SSE는 BFF를 거치지 않는다. BFF가 발급한 **단기 SSE 티켓**으로 브라우저가 Gateway에 직접 연결한다. (Node 프로세스가 장기 연결을 붙드는 것을 피하기 위함)

## 8. BFF (Next.js)

- 일반 API 요청은 전부 BFF를 거친다.
- 조합(Aggregation) 화면
  - 공연 상세: 공연 정보 + 회차별 잔여 좌석 + 내 예매 여부
  - 마이페이지: 예매 내역 + 결제 상태
  - 주최자 대시보드: 판매 현황 + 정산
- 주최자 화면은 최소한으로 만들고, 초기 공연 데이터는 시드 스크립트로 넣는다.

## 9. Batch

| 작업 | 내용 |
|---|---|
| 일별 정산 | 공연·주최자별 매출 집계, 수수료 계산. jOOQ reader, chunk 처리 |
| 미결제 예매 정리 | `PENDING`으로 남은 건을 `EXPIRED`로 전환 (Redis TTL 누락분 보정) |
| 결제 대사 | 가짜 PG 거래 내역과 내부 결제 기록의 불일치 검출 |

코어와 같은 코드베이스의 별도 실행 모듈로 두고, 추후 k8s CronJob으로 옮긴다.

## 10. 외부 연동

- 결제: **가짜 PG 서버**를 직접 만든다. 지연, 타임아웃, 실패, 중복 웹훅을 설정으로 재현한다. 출력 포트로 추상화하여 추후 실제 PG 어댑터를 추가할 수 있게 한다.
- 알림: 로그 출력으로 시작.

## 11. 개발 환경과 테스트

- 로컬 실행: Docker Compose (PostgreSQL, Redis, 가짜 PG)
- 통합 테스트: Testcontainers
- 모듈 테스트: Modulith 모듈 단위 테스트(`@ApplicationModuleTest`)
- 동시성 테스트: 같은 좌석에 대한 동시 선점·확정
- 부하 테스트: k6, "1만 명 동시 진입" 시나리오. 개선 전후 수치를 기록한다.
- 관측: Actuator로 시작. 부하 테스트 단계에서 Micrometer + Prometheus + Grafana + 분산 추적으로 확장.

## 12. 의도적으로 미룬 것

| 항목 | 도입 시점 |
|---|---|
| 소셜 로그인 (카카오/구글) | BFF 화면 완성 이후 |
| Spring Authorization Server (OAuth2/OIDC) | MSA 전환 시 |
| Kafka (Modulith 이벤트 외부화) | MSA 전환 시. 그 전까지 앱 간 통신은 Redis Streams |
| 실제 PG 연동 (테스트 모드) | 가짜 PG로 보상 흐름 검증 이후 |
| 실제 이메일/SMS 알림 | 미정 |
| 판매 통계 집계 Batch | 핵심 Batch 3종 이후 |
| 플랫폼 관리자 역할 | 미정 |
| 등급별 수량제 좌석 | 미정 |
| 전체 관측 스택 | 부하 테스트 단계 |
| k8s 배포 | MSA 전환과 함께 |
