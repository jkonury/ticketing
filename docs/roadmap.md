# 구현 로드맵

- 기준 문서: [design.md](./design.md)
- 작성일: 2026-11-10
- 상태: M0 완료, M1 미착수

각 단계는 **완료 기준**을 만족해야 다음 단계로 넘어간다. 완료한 항목은 체크하고, 설계와 달라진 점은 `design.md`를 함께 고친다.

## 단계 요약

| 단계 | 내용 | 결과물 |
|---|---|---|
| M0 | 프로젝트 뼈대 | 빌드되고 빈 앱이 뜨는 모노레포 |
| M1 | 코어 예매 흐름 | 가입 → 선점 → 결제 → 확정 (API 수준) |
| M2 | Batch | 정산, 미결제 정리, 결제 대사 |
| M3 | Gateway + 실시간 앱 | 대기열, 입장 토큰, SSE |
| M4 | Next.js BFF | 토큰 관리, 조합 화면 |
| M5 | 부하 테스트와 관측 | k6 결과, 개선 기록 |
| M6 | MSA + k8s | 모듈 분리, Kafka, 배포 |

---

## M0. 프로젝트 뼈대

- [x] 버전 확정: Spring Boot 4.x, Spring Modulith, Spring Cloud Gateway, Kotlin, jOOQ, Java 25의 호환 조합을 공식 문서로 확인하고 `design.md` 2장에 기록
- [x] `git init`, `.gitignore`, `.editorconfig`
- [x] Gradle 멀티 모듈 구성 (Kotlin DSL, version catalog): `core`, `core-batch`, `realtime`, `gateway`, `fake-pg`
- [x] Kotlin 플러그인 설정: `spring`(allopen), `jpa`(noarg)
- [x] `core`: 메인 클래스 `io.github.ticketing`, Virtual Thread 활성화, 모듈 패키지 6개 생성
- [x] Docker Compose: PostgreSQL, Redis
- [x] Flyway: 모듈별 스키마(`member`, `catalog`, `booking`, `payment`, `settlement`) 생성 마이그레이션
- [x] jOOQ 코드 생성 설정 (Flyway 스키마 기준)
- [x] Testcontainers 기반 통합 테스트 베이스
- [x] Modulith 검증 테스트(`ApplicationModules.verify()`), ArchUnit 규칙(헥사고날 의존 방향)
- [x] Modulith 이벤트 발행 레지스트리 설정

**완료 기준**: `./gradlew build` 통과, `core`가 Compose 환경에서 기동, 검증 테스트 통과.

---

## M1. 코어 예매 흐름

### M1-1. member
- [ ] 회원 가입, 로그인
- [ ] JWT access/refresh 발급·갱신
- [ ] 역할(일반 사용자, 주최자)과 역할 기반 인가
- [ ] Spring Security 필터 구성

### M1-2. catalog
- [ ] 공연, 회차, 공연장, 좌석 배치 모델
- [ ] 주최자의 공연·회차 등록 API
- [ ] 공연 목록·상세 조회 (jOOQ), Redis 조회 캐시
- [ ] 시드 데이터 스크립트

### M1-3. booking (헥사고날)
- [ ] 도메인 모델과 상태 전이 (`PENDING` / `CONFIRMED` / `EXPIRED` / `CANCELLED`)
- [ ] Redis 좌석 선점: Lua 원자적 다중 선점, TTL 5분, 최대 4매
- [ ] 예매 생성(`PENDING`) → 결제 요청 이벤트 발행
- [ ] 결제 결과 이벤트 수신 → 확정 또는 좌석 반환
- [ ] `(회차, 좌석)` 유니크 제약
- [ ] 사용자 취소
- [ ] 좌석 상태 변경을 Redis Streams로 발행, 좌석 스냅샷 갱신
- [ ] 내 예매 내역 조회 (jOOQ)

### M1-4. fake-pg
- [ ] 결제 요청 API, 웹훅 발송
- [ ] 지연·타임아웃·실패·중복 웹훅을 설정으로 제어
- [ ] 거래 내역 조회 API (결제 대사용)
- [ ] Docker Compose에 추가

### M1-5. payment (헥사고날)
- [ ] PG 출력 포트와 가짜 PG 어댑터
- [ ] 멱등 키 기반 결제 요청과 웹훅 중복 처리
- [ ] 결제 결과 이벤트 발행
- [ ] 환불, 만료 후 결제 성공 시 자동 환불

### M1-6. notification
- [ ] 예매 확정·취소 이벤트 수신 후 로그 출력

### M1-7. 검증
- [ ] 동시성 테스트: 같은 좌석 동시 선점 시 1명만 성공
- [ ] 동시성 테스트: 다중 좌석 선점의 전부 성공/전부 실패
- [ ] 실패 경로 테스트: 결제 실패, 웹훅 중복, 웹훅 지연으로 인한 만료 후 결제
- [ ] 모듈 단위 테스트(`@ApplicationModuleTest`)

### M1-8. 문서 정리
- [ ] `docs/design.md`를 `docs/architecture.md`로 승격: 새로 쓰지 않고 옮기면서 실제 구조에 맞게 고친다. 합의일·상태 등 기록성 항목은 제거하고, "의도적으로 미룬 것" 절은 이 로드맵으로 옮긴다
- [ ] 예매 흐름 시퀀스 다이어그램 추가 (대기열 제외: 선점 → 결제 → 확정, 실패 경로 포함)
- [ ] Modulith `Documenter`로 모듈 의존 다이어그램 생성
- [ ] 이벤트 목록(발행자, 구독자, 페이로드)과 테이블 구조(ERD) 추가
- [ ] `design.md`를 가리키는 모든 참조를 `architecture.md`로 수정: `README.md`, `CLAUDE.md`, `docs/rules/backend.md`, `.claude/rules/frontend.md`, `docs/adr/README.md`, 이 로드맵 (`grep -rn "design.md"`로 확인)

**완료 기준**: 가입 → 로그인 → 좌석 선점 → 가짜 결제 → 예매 확정이 API 테스트로 통과하고, 동시성·실패 경로 테스트가 모두 통과. `docs/architecture.md`가 실제 코드 구조와 일치.

---

## M2. Batch

- [ ] `core-batch` 실행 모듈 구성 (코어 코드 공유, 웹 서버 없이 실행)
- [ ] `settlement`가 결제·예매 이벤트를 받아 자기 스키마에 사본 적재
- [ ] 일별 정산 Job: jOOQ reader, chunk 처리, 수수료 계산, 재실행 안전성
- [ ] 미결제 예매 정리 Job: `PENDING` 만료분을 `EXPIRED`로 전환
- [ ] 결제 대사 Job: 가짜 PG 거래 내역과 내부 기록 불일치 검출·기록
- [ ] 주최자용 정산 조회 API
- [ ] Job 단위 테스트, 재시작·중복 실행 테스트

**완료 기준**: 세 Job이 로컬에서 실행되고, 같은 날짜로 다시 실행해도 결과가 중복되지 않는다.

---

## M3. Gateway + 실시간 앱

### M3-1. gateway
- [ ] 코어·실시간 앱 라우팅
- [ ] 입장 토큰 검증 필터 (예매 API 대상)
- [ ] SSE 티켓 검증 필터
- [ ] rate limit (Redis)

### M3-2. realtime (WebFlux + Coroutines)
- [ ] 대기열: Redis Sorted Set 등록·순번 조회
- [ ] 입장 스케줄러: 일정 인원씩 입장 토큰 발급
- [ ] SSE: 대기 순번 push
- [ ] SSE: 좌석 현황 push (접속 시 스냅샷, 이후 Redis Streams 구독분)
- [ ] 연결 종료·재연결 처리 (`Last-Event-ID`)
- [ ] PostgreSQL 의존이 없음을 빌드 의존성으로 확인

### M3-3. 검증
- [ ] 입장 토큰 없이 예매 API 호출 시 차단
- [ ] 실시간 앱 인스턴스 2개에서 순번·좌석 push 일관성

**완료 기준**: 대기열 진입 → 순번 SSE 수신 → 입장 토큰 발급 → Gateway 통과 → 예매 확정, 그리고 다른 사용자의 화면에 좌석 변경이 SSE로 도달.

---

## M4. Next.js BFF

- [ ] `web` 프로젝트 생성 (App Router, TypeScript)
- [ ] 로그인: BFF가 토큰을 서버 측에 보관, 브라우저에는 httpOnly 쿠키 세션만 전달
- [ ] access 토큰 만료 시 서버 측 자동 갱신
- [ ] 단기 SSE 티켓 발급 엔드포인트
- [ ] 조합: 공연 상세 (공연 정보 + 회차별 잔여 좌석 + 내 예매 여부)
- [ ] 조합: 마이페이지 (예매 내역 + 결제 상태)
- [ ] 조합: 주최자 대시보드 (판매 현황 + 정산)
- [ ] 화면: 공연 목록, 대기열, 좌석 선택(SSE 반영), 결제, 예매 완료
- [ ] 화면: 주최자 공연 등록 (최소 수준)
- [ ] 하위 API 일부 실패 시 부분 응답 처리

**완료 기준**: 브라우저에서 전체 예매 시나리오가 동작하고, 브라우저 저장소와 네트워크 응답 어디에도 JWT가 노출되지 않는다.

---

## M5. 부하 테스트와 관측

- [ ] Micrometer + Prometheus + Grafana, 분산 추적
- [ ] k6 시나리오: 1만 명 동시 진입, 인기 좌석 집중 선점
- [ ] 기준 수치 측정 후 병목 개선, 전후 수치 기록
- [ ] Virtual Thread 관련 점검 (pinning, 커넥션 풀 크기)
- [ ] 결과를 `docs/`에 문서화

**완료 기준**: 부하 상황에서 좌석 중복 판매 0건, 개선 전후 수치가 문서로 남아 있다.

---

## M6. MSA + k8s

- [ ] `payment` 분리: 별도 배포 단위, 스키마를 별도 DB로 이전
- [ ] `settlement` 분리
- [ ] Modulith 이벤트 외부화로 Kafka 도입, 앱 간 Redis Streams 대체 범위 결정
- [ ] 예매–결제 Saga 정리 (보상 흐름 재검증)
- [ ] 인증 구조 재검토 (Spring Authorization Server 도입 여부)
- [ ] 컨테이너 이미지 빌드
- [ ] k8s 매니페스트: Deployment, Service, Ingress, Batch는 CronJob
- [ ] 실시간 앱 수평 확장(HPA) 검증

**완료 기준**: k8s 클러스터에서 전체 시나리오와 Batch가 동작한다.

---

## 단계와 무관한 후속 항목

`design.md` 12장의 미룬 항목: 소셜 로그인, 실제 PG 연동, 실제 알림 발송, 판매 통계 Batch, 플랫폼 관리자 역할, 등급별 수량제 좌석.
