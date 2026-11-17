# 구현 로드맵

- 기준 문서: [architecture.md](./architecture.md)
- 작성일: 2026-11-10
- 상태: M0·M1·M2·M3·M4 완료, M5 완료 확정(아래 300ms 두 건은 장기 과제로 유지), M5.5 쿠폰 구현 0단계(문서 선행) 진행 중. M5 완료 기준(중복 판매 0건, 전후 수치 문서화) 충족, 단 B 예매 p95 300ms 잠정 기준 미달 잔존([최종 보고서](./performance/m5/11-step8-final-report.md)). 내부 예열 콜드 계열은 잠정 900ms 기준을 충족하고 300ms 장기 목표는 미달([문서 16](./performance/m5/16-startup-warmup-burst-volume.md)) (M0·M1 이력은 [backup/roadmap-completed-M0-M1.md](./backup/roadmap-completed-M0-M1.md))

각 단계는 **완료 기준**을 만족해야 다음 단계로 넘어간다. 완료한 항목은 체크하고, 설계와 달라진 점은 `architecture.md`를 함께 고친다.

## 단계 요약

| 단계 | 내용 | 결과물 |
|---|---|---|
| M0 | 프로젝트 뼈대 | 빌드되고 빈 앱이 뜨는 모노레포 |
| M1 | 코어 예매 흐름 | 가입 → 선점 → 결제 → 확정 (API 수준) |
| M2 | Batch | 정산, 미결제 정리, 결제 대사 |
| M3 | Gateway + 실시간 앱 | 대기열, 입장 토큰, SSE |
| M4 | Next.js BFF | 토큰 관리, 조합 화면 |
| M5 | 부하 테스트와 관측 | k6 결과, 개선 기록 |
| M5.5 | 쿠폰 | 관리자·감사, 예매–쿠폰 Saga, 선착순 발급 |
| M6 | MSA + k8s | 모듈 분리, Kafka, 배포 |

---

## M2. Batch

- [x] `core-batch` 실행 모듈 구성 (코어 코드 공유, 웹 서버 없이 실행)
- [x] `settlement`가 예매 확정·환불 취소 이벤트를 받아 자기 스키마에 사본 적재
- [x] 일별 정산 Job: jOOQ reader, chunk 처리, 수수료 계산, 재실행 안전성
- [x] 미결제 예매 정리 Job: `PENDING` 만료분을 `EXPIRED`로 전환
- [x] 좌석 스냅샷 보정 Job: 커밋 후 Redis 반영 실패로 어긋난 스냅샷을 DB 기준으로 보정
- [x] 결제 대사 Job: 가짜 PG 거래 내역과 내부 기록 불일치 검출·기록 (payment 모듈)
- [x] 주최자용 정산 조회 API
- [x] Job 단위 테스트, 재시작·중복 실행 테스트

**완료 기준**: 세 Job이 로컬에서 실행되고, 같은 날짜로 다시 실행해도 결과가 중복되지 않는다.

---

## M3. Gateway + 실시간 앱

### M3-1. gateway
- [x] 코어·실시간 앱 라우팅
- [x] 입장 토큰 검증 필터 (예매 API 대상)
- [x] SSE 티켓 검증 필터
- [x] rate limit (Redis)

### M3-2. realtime (WebFlux + Coroutines)
- [x] 대기열: Redis Sorted Set 등록·순번 조회
- [x] 입장 스케줄러: 일정 인원씩 입장 토큰 발급
- [x] SSE: 대기 순번 push
- [x] SSE: 좌석 현황 push (접속 시 스냅샷, 이후 Redis Streams 구독분)
- [x] 연결 종료·재연결 처리 (`Last-Event-ID`)
- [x] PostgreSQL 의존이 없음을 빌드 의존성으로 확인

### M3-3. 검증
- [x] 입장 토큰 없이 예매 API 호출 시 차단
- [x] 실시간 앱 인스턴스 2개에서 순번·좌석 push 일관성

**완료 기준**: 대기열 진입 → 순번 SSE 수신 → 입장 토큰 발급 → Gateway 통과 → 예매 확정, 그리고 다른 사용자의 화면에 좌석 변경이 SSE로 도달.

---

## M4. Next.js BFF

- [x] `web` 프로젝트 생성 (App Router, TypeScript)
- [x] 로그인: BFF가 토큰을 서버 측에 보관, 브라우저에는 httpOnly 쿠키 세션만 전달
- [x] access 토큰 만료 시 서버 측 자동 갱신
- [x] 단기 SSE 티켓 발급 엔드포인트
- [x] 조합: 공연 상세 (공연 정보 + 회차별 잔여 좌석 + 내 예매 여부)
- [x] 조합: 마이페이지 (예매 내역 + 결제 상태)
- [x] 조합: 주최자 대시보드 (판매 현황 + 정산)
- [x] 화면: 공연 목록, 대기열, 좌석 선택(SSE 반영), 결제, 예매 완료
- [x] 화면: 주최자 공연 등록 (최소 수준)
- [x] 하위 API 일부 실패 시 부분 응답 처리

**완료 기준**: 브라우저에서 전체 예매 시나리오가 동작하고, 브라우저 저장소와 네트워크 응답 어디에도 JWT가 노출되지 않는다.

---

## M5. 부하 테스트와 관측

- [x] Micrometer + Prometheus + Grafana, 분산 추적
  - [x] Scouter Gateway·realtime 설정 분리, SSE 계측 비용 축소 및 개발·부하 테스트 설정 선택 통일 ([Gatling ON/OFF 각 3회 비교](./performance/scouter-gatling-comparison.md): OOM 0건. 패치 에이전트로 reactive 훅 비용 제거, [ADR 0027](./adr/0027-scouter-reactive-txid-context-propagation.md))
- [x] k6 시나리오: 1만 명 동시 진입, 인기 좌석 집중 선점
- [x] 기준 수치 측정 후 병목 개선, 전후 수치 기록
  - 기준 측정 완료: [m5-baseline.md](./performance/m5-baseline.md)
  - 개선 확인: 대기열·입장 경합 수정으로 A 예상 밖 오류 7.65% → 0%·입장 약 7,000 → 10,000명, B 정시 준비 1,000/1,000 (Step 2 전후 3회 등, [최종 보고서](./performance/m5/11-step8-final-report.md))
  - **미해결**: B 예매 생성 p95 잠정 기준 300ms 미달(최종 콜드 1,385ms, 워밍업 383ms). 원인 미확정, 후속 과제
  - [ ] [Gatling hot-seats 1,000명 p95 300ms 후속 계획](./plans/hot-seats-gatling-p95-300ms.md): **미달로 종결하고 장기 과제로 이관**(2026-11-16). 15회 예열 독립 5회 판정 D 중앙값 386ms, 5회 모두 300ms 미달. Scouter ON/OFF 비교는 [Gatling 인계](./plans/hot-seats-gatling-handoff.md)에 기록. 체크하지 않음
  - 콜드 재정의: readiness 전 내부 예열 허용([ADR 0028](./adr/0028-cold-definition-internal-warmup.md)). 외부 15회 예열도 350~386ms로 300ms 미달인 조건부 목표다([외부 예열 최종 보고](./performance/m5/12-gatling-hot-seats-p95-300ms-report.md)). 이후 사용자 승인으로 내부 예열을 구현했다([ADR 0029](./adr/0029-layered-startup-warmup.md))
  - [ ] [콜드 p95 900ms(잠정, 장기 목표 300ms): 기동 시 내부 예열 계획](./plans/hot-seats-internal-warmup-cold-900ms.md): 3.2 기능 구현 후 [HTTP 경유 15회 × 1,000건으로 확대](./performance/m5/16-startup-warmup-burst-volume.md), [실제 핸들러 경유](./performance/m5/17-warmup-through-real-handler.md)·[트래픽 혼합](./performance/m5/18-warmup-traffic-mix.md)으로 이어졌다. 전체 테스트·예열 활성 E2E·품질 검사 통과. JFR core Interpreted 23.1%로 외부 예열 수준 미달이라 3.2 완료 기준은 미충족이며 체크하지 않는다. JFR OFF 독립 3회 전체·성공 p95는 16 시점 741·843·853ms / 710·784·777ms, 현재 코드(18) 760·772·750ms / 677·707·707ms로 900ms 기준을 모두 충족하고 300ms는 모두 미달(17의 709·1,458·816ms는 2회차 900ms 미달). 최신 코드의 JFR Interpreted 비율은 재집계하지 않았다. [TCP 진단(19)](./performance/m5/19-warmup-tcp-diagnosis.md)은 중단·미완료. 정합성·오류·SSE 통과, 순수 콜드 회귀는 사용자 요청으로 생략
  - [x] booking 불변 좌석 사본 캐시 구현·회귀 검증 ([ADR 0025](./adr/0025-booking-seat-copy-cache.md))
  - M5-2 계획: [병목 개선과 전후 비교](./plans/m5-2-improvements.md)
- [x] Virtual Thread 관련 점검 (pinning, 커넥션 풀 크기)
  - 기준 측정 완료: [m5-baseline.md](./performance/m5-baseline.md)
  - 최종 JFR·풀 결론: 정상 상태 pinning 0건(첫 폭주 구간의 클래스 로딩만), 풀 크기 10 유지(풀 20 기각)
- [x] 결과를 `docs/`에 문서화
  - 기준 측정: [m5-baseline.md](./performance/m5-baseline.md), 개선·최종 보고서: [m5-improvements.md](./performance/m5-improvements.md)

**완료 기준**: 부하 상황에서 좌석 중복 판매 0건, 개선 전후 수치가 문서로 남아 있다.

---

## M5.5. 쿠폰

계획서: [m5-5-coupon.md](./plans/m5-5-coupon.md). 결정 근거는 ADR 0034~0037이다.

### M5.5-1. 관리자·감사

- [x] `member`: `Role.ADMIN` 추가, 가입 시 ADMIN 요청 400, `.env` 이메일 부트스트랩, 역할 변경 API(마지막 관리자 강등 금지, 변경 시 refresh 토큰 폐기), 회원 목록 조회
- [x] `audit` 모듈(계층형): `audit.admin_action`, `MemberRoleChanged` 구독(`source_event_id` 유니크로 멱등), 감사 로그 조회 API, 모듈 검증·다이어그램 갱신
- [x] web: ADMIN 역할, `/admin` 이중 가드(BFF·코어), `/admin/members`, `/admin/audit-logs`, Origin 검증
- [x] 테스트: 가입 ADMIN 거부, 부트스트랩 멱등, 마지막 관리자 강등 거부, 갱신 실패, 비관리자 403, 감사 로그 멱등, web 가드

### M5.5-2. 쿠폰 적용 Saga

- [x] `coupon` 모듈(헥사고날): 정의·발급 쿠폰, 할인 계산(정액·정률), 수명주기 규칙, 관리자 API(생성·수정·중지·현황·직접 지급·회수), 사용자 받기·조회 API
- [x] 계약 이벤트 5종(예약 요청·예약됨·거부·해제 요청·사용 확정)과 멱등·조건부 UPDATE, 늦은 사용 확정 강제 반영과 불일치 기록
- [x] 고아 예약 정리 Job
- [x] `booking`: 금액 3종(`list`/`discount`/`payable`), 쿠폰 적용·해제·교체 API, 결제 요청 시 `COUPON_PENDING`, 복원 지점별 해제 요청, 확정 시 사용 확정
- [x] `settlement`: 정가 기준 집계와 `platformDiscountAmount` 기록
- [x] web: 결제 화면 쿠폰 적용, 내 쿠폰, 받기 페이지, `/admin/coupons`
- [x] 테스트: 도메인 경계, Saga 통합(USED·복원·소멸·늦은 Reserved·재전달·늦은 USED), 정리 Job, 정산 Job, E2E 세 경로, Playwright

### M5.5-3. 선착순 발급

- [x] Redis Lua(재고 + 중복 Set) 뒤 동기 DB INSERT, 실패 시 보상, Redis–DB 보정 (2cc628c, b775249)
- [x] gateway: 쿠폰 받기 라우트와 rate limit 버킷(c8d2abc, `coupon-claim` 보충 1·버스트 3 임시값 확정)
- [x] 부하 테스트 `coupon-drop`(1만 VU, 100장)과 정합성 검증 스크립트 (Gatling 하네스 effeca5, [결과](performance/m5.5/coupon-drop.md))
- [x] DB 조건부 UPDATE 방식 비교 실험과 결과 문서화 ([결과](performance/m5.5/coupon-drop.md))
- [x] 회귀: hot-seats 중복 판매 0건 유지 (확정 98석 중복 0, [결과](performance/m5.5/coupon-drop.md))

**완료 기준**: (1) 예매–쿠폰 E2E 세 경로(USED, 복원, 소멸) 통과 (2) 쿠폰을 쓴 예매의 정산이 정가 기준과 일치 (3) coupon-drop에서 초과·중복 발급 0건이고 Redis–DB 대사 일치 (4) hot-seats 중복 판매 0건 유지.

---

## M6. MSA + k8s

- [ ] `payment` 분리: 별도 배포 단위, 스키마를 별도 DB로 이전
- [ ] `settlement` 분리
- [ ] Modulith 이벤트 외부화로 Kafka 도입, 앱 간 Redis Streams 대체 범위 결정
- [ ] 예매–결제 Saga 정리 (보상 흐름 재검증, 쿠폰 보상(예약 해제·사용 확정) 포함)
- [ ] 인증 구조 재검토 (Spring Authorization Server 도입 여부)
- [ ] 컨테이너 이미지 빌드
- [ ] k8s 매니페스트: Deployment, Service, Ingress, Batch는 CronJob
- [ ] 실시간 앱 수평 확장(HPA) 검증

**완료 기준**: k8s 클러스터에서 전체 시나리오와 Batch가 동작한다.

---

## 단계와 무관한 후속 항목

의도적으로 미룬 항목이다. 요청 없이 앞당겨 구현하지 않는다.

| 항목 | 도입 시점 |
|---|---|
| 소셜 로그인 (카카오/구글) | BFF 화면 완성 이후 |
| Spring Authorization Server (OAuth2/OIDC) | MSA 전환 시 |
| Kafka (Modulith 이벤트 외부화) | MSA 전환 시. 그 전까지 앱 간 통신은 Redis Streams |
| 실제 PG 연동 (테스트 모드) | 가짜 PG로 보상 흐름 검증 이후 |
| 실제 이메일/SMS 알림 | 미정 |
| 판매 통계 집계 Batch | 핵심 Batch 3종 이후 |
| 플랫폼 실수익 리포트 (할인 부담 반영) | 미정 |
| coupon MSA 분리 | M6에서는 분리하지 않음. 이후 검토 |
| 등급별 수량제 좌석 | 미정 |
| k8s 배포 | MSA 전환과 함께 |
| coupon_claim p95 1초 (Gateway의 core 풀 대기 감소) | M5.5-3 측정 후속. [결과](performance/m5.5/coupon-drop.md) |
| 쿠폰 소진 경로의 DB 미접근 (정의 캐시) | M5.5-3 측정 후속 |
| `coupon-claim` rate limit 수치 검증 테스트 | M5.5-3 측정 후속. 현재 값은 임시 |
