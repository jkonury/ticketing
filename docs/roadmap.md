# 구현 로드맵

- 기준 문서: [architecture.md](./architecture.md)
- 작성일: 2026-11-10
- 상태: M0·M1·M2·M3·M4 완료, M5 완료 기준(중복 판매 0건, 전후 수치 문서화) 충족, 단 B 예매 p95 300ms 잠정 기준 미달 잔존([최종 보고서](./performance/m5/11-step8-final-report.md)) (M0·M1 이력은 [backup/roadmap-completed-M0-M1.md](./backup/roadmap-completed-M0-M1.md))

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
  - [x] Scouter Gateway·realtime 설정 분리, SSE 계측 비용 축소 및 개발·부하 테스트 설정 선택 통일 ([Gatling ON/OFF 각 3회 비교](./performance/scouter-gatling-comparison.md): OOM 0건, ON 성능 비용 잔존)
- [x] k6 시나리오: 1만 명 동시 진입, 인기 좌석 집중 선점
- [x] 기준 수치 측정 후 병목 개선, 전후 수치 기록
  - 기준 측정 완료: [m5-baseline.md](./performance/m5-baseline.md)
  - 개선 확인: 대기열·입장 경합 수정으로 A 예상 밖 오류 7.65% → 0%·입장 약 7,000 → 10,000명, B 정시 준비 1,000/1,000 (Step 2 전후 3회 등, [최종 보고서](./performance/m5/11-step8-final-report.md))
  - **미해결**: B 예매 생성 p95 잠정 기준 300ms 미달(최종 콜드 1,385ms, 워밍업 383ms). 원인 미확정, 후속 과제
  - [x] booking 불변 좌석 사본 캐시 구현·회귀 검증 ([ADR 0025](./adr/0025-booking-seat-copy-cache.md))
  - M5-2 계획: [병목 개선과 전후 비교](./plans/m5-2-improvements.md)
- [x] Virtual Thread 관련 점검 (pinning, 커넥션 풀 크기)
  - 기준 측정 완료: [m5-baseline.md](./performance/m5-baseline.md)
  - 최종 JFR·풀 결론: 정상 상태 pinning 0건(첫 폭주 구간의 클래스 로딩만), 풀 크기 10 유지(풀 20 기각)
- [x] 결과를 `docs/`에 문서화
  - 기준 측정: [m5-baseline.md](./performance/m5-baseline.md), 개선·최종 보고서: [m5-improvements.md](./performance/m5-improvements.md)

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

의도적으로 미룬 항목이다. 요청 없이 앞당겨 구현하지 않는다.

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
| k8s 배포 | MSA 전환과 함께 |
