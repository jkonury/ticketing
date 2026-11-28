# 구현 로드맵

- 기준 문서: [architecture.md](./architecture.md)
- 작성일: 2026-11-10
- 상태: M0~M5.5 완료. M5 완료 확정, M5.5 쿠폰 완료(M5.5-1·2·3 게이트 통과, coupon-drop p95 1초는 후속 항목, [결과](./performance/m5.5/coupon-drop.md)). M5 미달 성능 목표 3건(300ms·900ms 계열)은 장기 과제로 후속 항목에 이관. M6 진행 중: 사전 작업, payment·settlement 분리, 예매–결제 Saga 재검증 완료. (이력: [M0·M1](./backup/roadmap-completed-M0-M1.md), [M2·M3·M4·M5·M5.5](./backup/roadmap-completed-M2-M5.5.md))

AI 개발 워크플로 작업은 별도 트랙 [workflow/roadmap.md](./workflow/roadmap.md)(W 트랙), web UI 개편은 [ui/roadmap.md](./ui/roadmap.md)(U 트랙)로 병행한다.

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

## M6. MSA + k8s

계획: [m6-msa.md](./plans/m6-msa.md). 결정: [ADR 0039](./adr/0039-kafka-externalizes-modulith-events-only.md), [0040](./adr/0040-payment-separate-database-same-instance.md), [0041](./adr/0041-auth-stays-in-core-sas-deferred.md).

### 사전 작업 (본 작업 전에 완료)

- [x] P1 CI 파이프라인(GitHub Actions)
- [x] P2 Batch E2E 기준선(정산·대사·미결제 정리)
- [x] P3 공용 클래스 `common` Gradle 모듈 추출
- [x] P4 계약 이벤트 `contract` 모듈 추출, 직렬화 형식(JSON, 하위 호환 규칙) 고정
- [x] P5 `event_publication` 미완료 이벤트 관측과 재처리 runbook
- [x] P6 (선택) 분리 전 성능 기준선 1회 측정 — [k6](performance/m6/00-pre-split-baseline.md), [Gatling·결제 확정 지연(비교 기준)](performance/m6/01-pre-split-baseline-gatling.md)

### 본 작업

- [x] Modulith 이벤트 외부화로 Kafka 도입: 모놀리스 상태에서 먼저 적용·검증 (앱 간 Redis Streams는 유지: ADR 0039)
- [x] `payment` 분리: 별도 배포 단위, 스키마를 별도 DB로 이전, fake-pg 웹훅 수신 경로 이전, [분리 후 결제 확정 지연 측정](./performance/m6/03-payment-split-gatling.md)
- [x] HTTP API 계약 코드 생성: core·payment·realtime OpenAPI 스펙(springdoc) → web BFF 경계 Zod 스키마 생성, 재생성 diff로 drift 차단 ([ADR 0047](./adr/0047-openapi-codegen-for-bff-boundary.md))
- [x] `settlement` 분리: 전용 DB·Kafka 소비·일별 배치, Gateway 정산 조회, 이전 리허설 및 P2 기준선 검증 ([실행 계획](./workflow/plans/2026-11-27-m6-settlement-split.md), [이전 런북](./runbook.md#10-정산-db-이전-core-db-settlement-스키마--settlement_db))
- [x] 예매–결제 Saga 정리 (보상 흐름 재검증, 쿠폰 보상(예약 해제·사용 확정) 포함) ([검증 결과](./workflow/plans/2026-11-27-m6-booking-payment-saga.md#검증-결과))
- [x] 인증 구조 재검토 (Spring Authorization Server 도입 여부): M6에서는 유지, SAS는 후속 (ADR 0041)
- [ ] 컨테이너 이미지 빌드
- [ ] k8s 매니페스트: Deployment, Service, Ingress, Batch는 CronJob
- [ ] 실시간 앱 수평 확장(HPA) 검증

**완료 기준**: k8s 클러스터에서 전체 시나리오와 Batch가 동작한다.

---

## 별도 구현 항목

- [x] 관리자 기능 강화. 공연 판매 중지·재개, 회원 이용 정지·해제, 관리자 예매·결제 조회 ([실행 계획](./workflow/plans/2026-11-28-admin-enhancement.md), [ADR 0052](./adr/0052-admin-intervention-states.md))
- [x] 개발·CI 포스터 저장소 RustFS 전환 ([실행 계획](./workflow/plans/2026-11-28-poster-storage-rustfs.md), [ADR 0053](./adr/0053-poster-storage-rustfs.md))

## 단계와 무관한 후속 항목

의도적으로 미룬 항목이다. 요청 없이 앞당겨 구현하지 않는다.

### 우선 처리 순서 (성능 항목 제외, 2026-11-28 정리)

도입 조건을 충족한 항목을 처리 순서대로 적는다. M6 잔여 작업(컨테이너 이미지, k8s 매니페스트, HPA)과 함께 진행한다.

| 순서 | 항목 | 도입 시점 |
|---|---|---|
| 1 | 관리자 기능 후속 | 예매 강제 취소·공연 취소 Saga 먼저, 정산 전체 조회·운영 도구·대시보드는 그다음. [ADR 0052](./adr/0052-admin-intervention-states.md) |
| 2 | 실제 PG 연동 (테스트 모드) | 가짜 PG 보상 흐름 검증 완료(M6 Saga 재검증). k8s Secret 구성 이후 |
| 3 | 판매 통계 집계 Batch | 핵심 Batch 3종(M2) 완료. settlement의 Kafka 소비 구조에 붙인다 |
| - | `coupon-claim` rate limit 수치 검증 테스트 | M5.5-3 측정 후속. 현재 값은 임시. 범위가 작아 다른 작업 사이에 처리 |

### 보류 항목

| 항목 | 도입 시점 |
|---|---|
| 소셜 로그인 (카카오/구글) | BFF 화면은 완성(U0~U5). 인증 구조가 겹치므로 SAS 결정 이후 |
| Spring Authorization Server (OAuth2/OIDC) | M6 완료 후 새 ADR로 검토. [ADR 0041](./adr/0041-auth-stays-in-core-sas-deferred.md) |
| coupon MSA 분리 | M6에서는 분리하지 않음. 이후 검토 |
| 실제 이메일/SMS 알림 | 미정 |
| 플랫폼 실수익 리포트 (할인 부담 반영) | 미정 |
| 등급별 수량제 좌석 | 미정 |

### 성능 후속 항목

| 항목 | 도입 시점 |
|---|---|
| coupon_claim p95 1초 (Gateway의 core 풀 대기 감소) | M5.5-3 측정 후속. [결과](performance/m5.5/coupon-drop.md) |
| 쿠폰 소진 경로의 DB 미접근 (정의 캐시) | M5.5-3 측정 후속 |
| Gatling hot-seats 1,000명 p95 300ms | 장기 과제. 15회 예열 독립 5회 중앙값 386ms로 모두 미달. [후속 계획](./plans/hot-seats-gatling-p95-300ms.md), [외부 예열 최종 보고](./performance/m5/12-gatling-hot-seats-p95-300ms-report.md) |
| 콜드 p95 300ms (잠정 900ms 기준은 충족) | 장기 과제. 내부 예열 후 3회 측정 모두 300ms 미달. [계획](./plans/hot-seats-internal-warmup-cold-900ms.md), [문서 18](./performance/m5/18-warmup-traffic-mix.md). 최신 코드의 JFR 재집계와 [TCP 진단(19)](./performance/m5/19-warmup-tcp-diagnosis.md)은 미완료 |
| B 예매 생성 p95 300ms (잠정 기준 미달) | 장기 과제. 최종 콜드 1,385ms, 워밍업 383ms. 원인 미확정. [최종 보고서](./performance/m5/11-step8-final-report.md) |
| 관리자 기능 후속 | 예매 강제 취소·공연 취소 Saga, 정산 전체 조회, 운영 도구, 대시보드. [ADR 0052](./adr/0052-admin-intervention-states.md) |
