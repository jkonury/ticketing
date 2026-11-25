# 구현 로드맵

- 기준 문서: [architecture.md](./architecture.md)
- 작성일: 2026-11-10
- 상태: M0~M5.5 완료. M5 완료 확정, M5.5 쿠폰 완료(M5.5-1·2·3 게이트 통과, coupon-drop p95 1초는 후속 항목, [결과](./performance/m5.5/coupon-drop.md)). M5 미달 성능 목표 3건(300ms·900ms 계열)은 장기 과제로 후속 항목에 이관. M6 진행 중: 사전 작업과 1·2단계 완료, payment 분리 진행 중(측정 남음). (이력: [M0·M1](./backup/roadmap-completed-M0-M1.md), [M2·M3·M4·M5·M5.5](./backup/roadmap-completed-M2-M5.5.md))

AI 개발 워크플로 작업은 별도 트랙 [workflow/roadmap.md](./workflow/roadmap.md)(W 트랙)로 병행한다.

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
- [ ] `payment` 분리: 별도 배포 단위, 스키마를 별도 DB로 이전, fake-pg 웹훅 수신 경로 이전
- [ ] HTTP API 계약 코드 생성: core·payment·realtime OpenAPI 스펙(springdoc) → web BFF 경계 zod 스키마 생성, 재생성 diff로 drift 차단. payment 분리 후 진행 ([ADR 0047](./adr/0047-openapi-codegen-for-bff-boundary.md), 제안)
- [ ] `settlement` 분리
- [ ] 예매–결제 Saga 정리 (보상 흐름 재검증, 쿠폰 보상(예약 해제·사용 확정) 포함)
- [x] 인증 구조 재검토 (Spring Authorization Server 도입 여부): M6에서는 유지, SAS는 후속 (ADR 0041)
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
| Spring Authorization Server (OAuth2/OIDC) | M6 완료 후 새 ADR로 검토. [ADR 0041](./adr/0041-auth-stays-in-core-sas-deferred.md) |
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
| Gatling hot-seats 1,000명 p95 300ms | 장기 과제. 15회 예열 독립 5회 중앙값 386ms로 모두 미달. [후속 계획](./plans/hot-seats-gatling-p95-300ms.md), [외부 예열 최종 보고](./performance/m5/12-gatling-hot-seats-p95-300ms-report.md) |
| 콜드 p95 300ms (잠정 900ms 기준은 충족) | 장기 과제. 내부 예열 후 3회 측정 모두 300ms 미달. [계획](./plans/hot-seats-internal-warmup-cold-900ms.md), [문서 18](./performance/m5/18-warmup-traffic-mix.md). 최신 코드의 JFR 재집계와 [TCP 진단(19)](./performance/m5/19-warmup-tcp-diagnosis.md)은 미완료 |
| B 예매 생성 p95 300ms (잠정 기준 미달) | 장기 과제. 최종 콜드 1,385ms, 워밍업 383ms. 원인 미확정. [최종 보고서](./performance/m5/11-step8-final-report.md) |
