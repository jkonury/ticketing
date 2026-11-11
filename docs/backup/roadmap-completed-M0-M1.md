# 구현 로드맵 완료 이력 (M0, M1)

- 원본: [roadmap.md](../roadmap.md)
- 이관일: 2026-11-11
- 용도: 완료된 단계의 체크리스트 기록 보관. 진행 중 작업의 기준 문서는 `docs/roadmap.md`이다.

---

## M0. 프로젝트 뼈대

- [x] 버전 확정: Spring Boot 4.x, Spring Modulith, Spring Cloud Gateway, Kotlin, jOOQ, Java 25의 호환 조합을 공식 문서로 확인하고 `architecture.md` 2장에 기록
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
- [x] 회원 가입, 로그인
- [x] JWT access/refresh 발급·갱신
- [x] 역할(일반 사용자, 주최자)과 역할 기반 인가
- [x] Spring Security 필터 구성

### M1-2. catalog
- [x] 공연, 회차, 공연장, 좌석 배치 모델
- [x] 주최자의 공연·회차 등록 API
- [x] 공연 목록·상세 조회 (jOOQ), Redis 조회 캐시
- [x] 시드 데이터 스크립트

### M1-3. booking (헥사고날)
- [x] 도메인 모델과 상태 전이 (`PENDING` / `CONFIRMED` / `EXPIRED` / `CANCELLED`)
- [x] Redis 좌석 선점: Lua 원자적 다중 선점, TTL 5분, 최대 4매
- [x] 예매 생성(`PENDING`) → 결제 요청 이벤트 발행
- [x] 결제 결과 이벤트 수신 → 확정 또는 좌석 반환
- [x] `(회차, 좌석)` 유니크 제약
- [x] 사용자 취소
- [x] 좌석 상태 변경을 Redis Streams로 발행, 좌석 스냅샷 갱신
- [x] 내 예매 내역 조회 (jOOQ)

### M1-4. fake-pg
- [x] 결제 요청 API, 웹훅 발송
- [x] 지연·타임아웃·실패·중복 웹훅을 설정으로 제어
- [x] 거래 내역 조회 API (결제 대사용)
- [x] Docker Compose에 추가

### M1-5. payment (헥사고날)
- [x] PG 출력 포트와 가짜 PG 어댑터
- [x] 멱등 키 기반 결제 요청과 웹훅 중복 처리
- [x] 결제 결과 이벤트 발행
- [x] 환불, 만료 후 결제 성공 시 자동 환불

### M1-6. notification
- [x] 예매 확정·취소 이벤트 수신 후 로그 출력

### M1-7. 검증
- [x] 동시성 테스트: 같은 좌석 동시 선점 시 1명만 성공
- [x] 동시성 테스트: 다중 좌석 선점의 전부 성공/전부 실패
- [x] 실패 경로 테스트: 결제 실패, 웹훅 중복, 웹훅 지연으로 인한 만료 후 결제
- [x] 모듈 단위 테스트(`@ApplicationModuleTest`): booking 회차 이벤트 수신, notification 확정·취소 이벤트 수신
- [x] 테스트 생성자 자동 주입 설정 및 필드 주입 제거
- [x] 테스트 컨텍스트·컨테이너 재사용 개선 ([측정 결과](../test-performance.md))

### M1-8. 문서 정리
- [x] design.md를 `docs/architecture.md`로 승격: 새로 쓰지 않고 옮기면서 실제 구조에 맞게 고친다. 합의일·상태 등 기록성 항목은 제거하고, "의도적으로 미룬 것" 절은 이 로드맵으로 옮긴다
- [x] 예매 흐름 시퀀스 다이어그램 추가 (대기열 제외: 선점 → 결제 → 확정, 실패 경로 포함)
- [x] Modulith `Documenter`로 모듈 의존 다이어그램 생성
- [x] 이벤트 목록(발행자, 구독자, 페이로드)과 테이블 구조(ERD) 추가
- [x] `design.md`를 가리키는 모든 참조를 `architecture.md`로 수정: `README.md`, `CLAUDE.md`, `docs/rules/backend.md`, `docs/rules/frontend.md`, `docs/adr/README.md`, 이 로드맵 (`grep -rn "design.md"`로 확인)

**완료 기준**: 가입 → 로그인 → 좌석 선점 → 가짜 결제 → 예매 확정이 API 테스트로 통과하고, 동시성·실패 경로 테스트가 모두 통과. `docs/architecture.md`가 실제 코드 구조와 일치.

