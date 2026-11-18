# M6 MSA + k8s 계획

- 작성일: 2026-11-18
- 상태: 초안. 열린 결정 3건은 합의 완료(ADR 0039~0041). 2단계 구현 결정은 ADR 0043. 구현 진행 중
- 관련: [로드맵](../roadmap.md), [아키텍처](../architecture.md), [이벤트 카탈로그](../architecture/events.md), [데이터 소유](../architecture/data-ownership.md), [ADR 0039](../adr/0039-kafka-externalizes-modulith-events-only.md), [ADR 0040](../adr/0040-payment-separate-database-same-instance.md), [ADR 0041](../adr/0041-auth-stays-in-core-sas-deferred.md)

## 1. 배경과 범위

로드맵 M6의 완료 기준은 "k8s 클러스터에서 전체 시나리오와 Batch가 동작한다"이다. 범위는 다음과 같다.

| 포함 | 제외 |
|---|---|
| `payment` 분리(DB 이전 포함), `settlement` 분리 | `coupon` 분리(이후 검토). Saga 재검증에는 쿠폰 보상을 포함한다 |
| Modulith 이벤트 외부화(Kafka) | 대기열·좌석 스트림의 Kafka 이전([ADR 0038](../adr/0038-queue-stays-on-redis-not-kafka.md), [ADR 0039](../adr/0039-kafka-externalizes-modulith-events-only.md)) |
| 컨테이너 이미지, k8s 매니페스트(Batch는 CronJob), realtime HPA | Spring Authorization Server 도입([ADR 0041](../adr/0041-auth-stays-in-core-sas-deferred.md)) |

현재 사실:

- 저장소에 CI(`.github/workflows`)와 Dockerfile이 없다.
- `payment`는 다른 도메인 모듈을 import하지 않는다. 루트 공용 클래스(`DomainException`, `ApiExceptionHandler`, `ClockConfig`, `KeysetItemReader`, 위치 `core/src/main/kotlin/io/github/ticketing/`)와 payment 루트의 결제 계약 이벤트 5종만 booking과 공유한다.
- `settlement`는 `booking.BookingConfirmed`·`BookingCancelled`만 import한다.
- Spring Modulith 2.1.1. `@Externalized`와 `spring-modulith-events-kafka`는 쓰지 않는다. `event_publication`을 쓰고 `republish-outstanding-events-on-restart: true`다(batch 프로필은 false).
- E2E는 `core/src/test/.../e2e` 7개(예매·동시성·결제 실패·웹훅 멱등·만료 후 환불)와 `e2e/` 6개(대기열·입장 토큰·SSE·쿠폰 Saga 3종)다. Batch는 P2의 `e2e/` `BatchBaselineE2eTests` 3개(아래 기준선)가 있다.

## 2. 사전 작업

M6 본 작업 전에 끝낸다. 로드맵의 "M6 사전 작업" 체크리스트와 같은 항목이다.

| 번호 | 항목 | 이유 |
|---|---|---|
| P1 | CI 파이프라인(GitHub Actions): `build`(ktlint·detekt 포함), web 검사, 문서 검사 | 분리 후 모듈이 늘어 로컬 검사만으로는 회귀를 놓친다 |
| P2 | Batch E2E 기준선: 정산·대사·미결제 정리 Job을 실제 DB로 실행해 결과를 고정 | 분리 전후 결과를 비교할 기준이 없다 |
| P3 | 공용 클래스를 `common` Gradle 모듈로 추출 | payment가 core 소스를 참조하지 않고 독립 빌드되게 한다 |
| P4 | 계약 이벤트를 `contract` 모듈로 추출하고 직렬화 형식 고정(JSON, 하위 호환 규칙. 버전 필드 없음, [ADR 0042](../adr/0042-contract-module-keeps-packages-and-json-compat-rules.md)). 쿠폰·`ScheduleRegistered`·audit 이벤트는 같은 JVM에 남아 대상이 아니다 | 프로세스 경계를 넘는 페이로드는 호환성 규칙이 필요하다([events.md §1](../architecture/events.md#1-공통-규칙)) |
| P5 | `event_publication` 미완료 이벤트 관측(건수·최고령 지표)과 재처리 runbook | Kafka 외부화 후 전송 실패는 이 테이블에 쌓인다 |
| P6 | (선택) 분리 전 성능 기준선 1회 측정 | 분리로 늘어나는 네트워크 홉의 비용을 비교한다. 결과: [00 k6](../performance/m6/00-pre-split-baseline.md), 비교 기준 [01 Gatling·결제 확정 지연](../performance/m6/01-pre-split-baseline-gatling.md) |

**P5 미완료 이벤트 관측**: Modulith 2.1.1은 `event_publication` 지표를 제공하지 않는다(staleness 모니터는 오래된 건을 `FAILED`로 표시할 뿐이다). 그래서 `EventPublicationMetrics`가 `ticketing_event_publication_incomplete`(건수)와 `ticketing_event_publication_oldest_incomplete_age_seconds`(최고령 경과)를 10초 캐시로 노출하고 core-batch에서는 끈다. Grafana `ticketing-overview`에 패널 2개를 추가했다. 재처리는 새 운영 API 없이 runbook([§3.7](../runbook.md#37-미완료-이벤트-발행이-쌓인다))의 SQL 조회와 core 재시작 재발행(`republish-outstanding-events-on-restart: true`)으로 한다. 재시작 없는 수동 재제출(`IncompleteEventPublications`를 쓰는 관리자 API)은 보안·감사 설계가 필요해 후속으로 미룬다.

후속(미포함): 완료된 `event_publication` 행 정리(삭제·`CompletedEventPublications` 이용). 현재는 행이 계속 쌓이며 `completion_date` 인덱스가 있어 지표 조회에는 영향이 작다. Kafka 외부화 후 이벤트량이 늘면 정리 정책과 알림 임계값을 정한다.

**P2 Batch 기준선**: `BatchBaselineE2eTests`가 `core-batch.jar`를 별도 프로세스(`java -jar`, k8s CronJob과 같은 형태)로 실행한다. `scripts/e2e/run.sh`가 jar 경로와 DB 접속 정보를 넘기고, 검증은 `docker compose exec psql`로 한다(정산·대사 결과 API가 없고 `batch` 스키마 상태도 읽어야 하므로). 같은 DB에 이전 실행 데이터가 남으므로 테스트가 만든 회차·주문으로만 검증한다. 분리 후(4단계 등)에도 같은 값이 나와야 한다.

| Job | 시나리오 | 기준선 |
|---|---|---|
| `dailySettlementJob` (`targetDate`=오늘 KST) | 정가 10000 좌석 3개: 일반 확정 1, 쿠폰 3000 할인 확정 1, 확정 후 취소(환불) 1 | 종료 코드 0, 실행 `COMPLETED`. 공연 행: 판매 3건·30000(정가 합), 환불 1건·10000, 순액 20000, 수수료율 0.10·수수료 2000, 정산액 18000, 플랫폼 할인 부담 3000 |
| `paymentReconciliationJob` (`targetDate`=오늘 KST) | 위 3건 결제(1건 환불 완료) | 종료 코드 0, `COMPLETED`. PG 스냅샷에 3건 존재, 3건의 불일치 0건 |
| `expirePendingBookingsJob` | PENDING 예매 1건의 `expires_at`을 DB에서 과거로 돌린 뒤 실행(보유 5분이 상수라 기다리지 않기 위함) | 종료 코드 0, `COMPLETED`. 예매 `EXPIRED`, 같은 좌석을 다른 구매자가 예매 가능 |

후속(미포함): `ReconcileSeatSnapshotJob`·`ReconcileCouponStockJob`·`ReleaseStaleCouponReservationsJob`, 대사 불일치 의도 검출 시나리오. 한계: `targetDate` 계산이 KST 자정 직전·직후에 걸리면 flaky할 수 있다.

P3·P4는 모듈 의존이 바뀌므로 Modulith 구조 검증(`ApplicationModules.verify()`)과 ArchUnit을 반드시 통과시킨다.

## 3. 본 작업 순서와 완료 기준

| 단계 | 내용 | 완료 기준 | 검증 방법 |
|---|---|---|---|
| 1 ✅ | contract·common 추출 마무리(P3·P4 결과 반영), 이벤트별 외부화 대상 표 확정([events.md](../architecture/events.md#kafka-외부화-대상-m6)) | core 빌드와 기존 테스트 통과. 이벤트 JSON 스키마 예시가 문서에 있다 | `./scripts/agent/test/run-tests.sh`, Modulith 검증 |
| 2 | Kafka 외부화([ADR 0039](../adr/0039-kafka-externalizes-modulith-events-only.md))를 **모놀리스 상태에서** 먼저 적용: `@Externalized`, payment 리스너가 Kafka로 결제 계약 이벤트(`PaymentRequested`·`RefundRequested`)를 받고 결제 결과 3종을 Kafka로 발행, 소비자 멱등, `serialize-externalization`·outbox 모드 검토 | core 안에서 결제 계약 이벤트 5종이 Kafka를 거쳐도 기존 E2E 13개가 통과한다. 같은 이벤트를 두 번 받아도 결과가 같다. 브로커 중단·복구 후 미완료 이벤트가 재전달된다. 결제 확정 지연(`ticketing_booking_payment_confirmation_lag`) p95를 [01 기준선](../performance/m6/01-pre-split-baseline-gatling.md)과 비교해 결과 문서에 기록한다(회귀 허용 폭은 정하지 않고 기록·비교까지만). | 소비자 멱등 테스트, 브로커 장애 주입, Testcontainers Kafka, `./scripts/e2e/run.sh`, Gatling `hot-seats` 콜드 3회(01과 같은 조건)로 확정 지연 p95 측정 |
| 3 | `payment` 분리: 별도 Gradle 앱, 별도 DB 이전([ADR 0040](../adr/0040-payment-separate-database-same-instance.md)), **fake-pg 웹훅 수신 경로 이전**(아래 상세) | payment 앱이 독립 기동하고 자기 DB와 `event_publication`을 쓴다. 이전 후 건수·체크섬 일치. 웹훅 멱등 키(`webhook_event.event_id`) 처리가 유지되고 기존 E2E(웹훅 멱등, 만료 후 환불)가 통과한다. 결제 확정 지연(`ticketing_booking_payment_confirmation_lag`) p95를 [01 기준선](../performance/m6/01-pre-split-baseline-gatling.md)과 비교해 결과 문서에 기록한다(회귀 허용 폭은 정하지 않고 기록·비교까지만). | 이전 절차 체크, 결제 E2E, 웹훅 중복 전송, Gatling `hot-seats` 콜드 3회(01과 같은 조건)로 확정 지연 p95 측정 |
| 4 | `settlement` 분리: `BookingConfirmed`·`BookingCancelled` 소비, 정산 Batch 구성 | 분리 후 정산 결과가 P2 기준선과 같다 | Batch E2E 비교 |
| 5 | 예매–결제·쿠폰 Saga 재검증: 결제 실패·웹훅 중복·만료 후 환불·쿠폰 예약 해제·사용 확정 | 기존 E2E 13개가 분리 환경에서 통과하고, 브로커·앱 중단 시나리오에서 보상 흐름이 완료된다 | `./scripts/e2e/run.sh` 확장, 장애 주입 |
| 6 | 컨테이너 이미지: 모듈별 Dockerfile, 이미지 빌드 | 모든 앱 이미지가 빌드되고 compose로 기동한다 | compose 기동 + E2E |
| 7 | k8s 매니페스트: Deployment, Service, Ingress, Batch CronJob, Kafka·PostgreSQL·Redis 구성 | 클러스터에서 전체 시나리오와 Batch가 동작한다 | 로컬 클러스터(kind 또는 minikube)에서 E2E, CronJob 수동 실행 |
| 8 | realtime HPA 검증 | 부하에서 replica가 늘고 SSE 연결이 유지되며 입장 순서가 깨지지 않는다 | k6 `queue-surge`, 메트릭 확인 |

**3단계 웹훅 경로 이전** (현재 사실):

- 웹훅 수신은 core의 `PaymentWebhookController`(`POST /api/v1/payments/webhooks/fake-pg`)다. 서명 검증은 `PaymentWebhookSignatureVerifier`, 멱등은 `payment.webhook_event`의 `event_id` 기본 키로 처리한다. `SecurityConfig`가 이 경로를 `permitAll`로 연다.
- fake-pg는 `fake-pg/src/main/resources/application.yml`의 `FAKE_PG_WEBHOOK_URL`(기본 `http://localhost:18080/api/v1/payments/webhooks/fake-pg`, core 직접 호출)로 보낸다. Gateway를 거치지 않는다. 서명 비밀은 `FAKE_PG_WEBHOOK_SECRET`(core `application.yml`과 공유)이다.
- 따라서 이전 때 할 일: ① fake-pg의 웹훅 URL을 payment 앱 주소로 바꾸고, ② 웹훅은 Gateway를 거치지 않으므로 웹훅용 Gateway 라우팅은 만들지 않는다(payment 앱의 보안 설정에서 해당 경로를 `permitAll`로 이전), ③ Gateway `core-api` 라우트(`/api/v1/**` → core)는 `GET /api/v1/payments/me` 같은 사용자 결제 API가 payment로 옮겨가므로 `/api/v1/payments/**`를 payment 앱으로 보내는 라우트를 추가한다, ④ E2E 스크립트(`scripts/e2e/run.sh`)와 부하 스크립트(`scripts/loadtest/`)의 포트·URL·프로세스 구성을 갱신한다.
- 완료 기준에 포함: 웹훅 멱등 키 처리 유지, 서명 검증 유지, 기존 E2E(웹훅 멱등, 만료 후 환불) 통과.

**2단계 세부 체크리스트** (작성 2026-11-18. 하위 작업 하나씩 진행하고 검증 통과 후 체크한다. 결정은 합의 완료)

결정(2026-11-18 합의, [ADR 0043](../adr/0043-kafka-externalization-step2-decisions.md). `serialize-externalization`·outbox는 2-5 측정 후 확정):

| 결정 | 선택 | 근거·확인 필요 |
|---|---|---|
| 로컬 리스너 전환 | 로컬 `@ApplicationModuleListener`를 **제거하고 같은 클래스에서 `@KafkaListener`로 교체**한다. 프로퍼티·프로필 토글은 두지 않는다 | 3단계 분리 후에도 코드가 그대로 쓰인다. 토글은 이중 처리 위험과 테스트 조합만 늘린다 |
| 본문 직렬화 | JSON 고정. 타입 헤더를 쓰지 않고 소비자가 리스너 파라미터 타입으로 역직렬화한다 | 헤더에 클래스명이 들어가면 분리 후 패키지·모듈이 달라질 때 깨진다. 공식 문서로 Modulith Kafka 외부화가 값을 어떻게 `KafkaTemplate`에 넘기는지 먼저 확인한다 |
| 골든 일치 확인 | 테스트에서 실제 브로커로 나간 메시지를 `String`으로 소비해 `contract` 골든 JSON과 트리 비교한다 | ADR 0042의 호환 규칙과 같은 골든 파일을 재사용한다 |
| 컨슈머 실패 처리 | `DefaultErrorHandler` + 지수 백오프 재시도(3회, 1s 시작) 후 **DLT(`<토픽>.dlt`)로 보낸다**. DLT 적재 건수는 지표로 노출하고 runbook에 재처리 절차를 둔다 | 재시도만 하고 버리면 결제 결과 유실로 예매가 `PENDING`에 남는다 |
| 소비자 멱등 보강 | `PaymentRequested`: `order_id`·`idempotency_key` 유니크 충돌을 정상 중복으로 처리하는지 확인. `RefundRequested`: 환불 진행 중 중복 도착 경합 테스트 추가 | events.md가 "2단계에서 추가 필요"로 표시한 항목 |
| `serialize-externalization`·outbox | **둘 다 채택하지 않는다**(잠정). 키가 `orderId`이고 소비자가 상태 전이 검사로 순서 역전을 흡수하며, 직렬화는 처리량을 낮춘다. 측정에서 확정 지연이 나쁘거나 순서 문제가 나오면 재검토한다 | 5번 측정 결과로 확정 |
| Kafka 기동 | compose 기본 서비스로 추가(프로필 분리 안 함, core가 항상 필요). 이미지는 공식 `apache/kafka`의 확인한 안정 버전, KRaft 단일 브로커, 호스트 포트 `127.0.0.1:19092`. 테스트는 Testcontainers `KafkaContainer`를 `SharedContainers`에 추가 | 2단계 이후 core는 Kafka 없이 결제가 안 된다. 계획 4절의 "프로필 분리"는 observability처럼 부가 스택에 해당하므로 조정(4절 수정 완료) |
| 파티션·브로커 | 토픽당 3, 단일 브로커 | 측정 후 조정 |

체크리스트:

- [x] **2-1 Kafka 인프라**: `compose.yaml` Kafka 서비스(데이터 볼륨 `KAFKA_LOG_DIRS`), Gradle 의존성(`spring-modulith-events-kafka`, `spring-boot-starter-kafka`, 테스트용 Testcontainers Kafka), `application.yml`·테스트 설정, 토픽 생성(`NewTopic` 빈) 
  - 완료 기준: `docker compose up -d` 후 core가 기동하고 토픽 5종이 존재한다. 테스트 컨텍스트가 Kafka 컨테이너를 공유한다
  - 검증: `bootRun` 기동 확인, `--tests` 필터로 컨텍스트 로딩 테스트
- [x] **2-2 발행**: 결제 계약 5종 `@Externalized`(토픽·키는 events.md 표), JSON 직렬화 고정
  - 완료 기준: 브로커로 나간 메시지가 골든 JSON과 같고 키가 `orderId`다. 로컬 리스너는 아직 유지(이중 소비 허용 상태)
  - 검증: 발행 테스트(Testcontainers Kafka), Modulith 구조 검증
- [x] **2-3 소비 전환·멱등**: payment 리스너 2종, booking 리스너 3종을 `@KafkaListener`로 교체하고 로컬 리스너 제거. 실패 처리(재시도·DLT) 적용, 멱등 보강
  - 완료 기준: 같은 이벤트를 두 번 보내도 결과가 같다(중복 수신 테스트). 영구 실패는 DLT로 간다
  - 검증: 소비자 멱등·환불 경합·DLT 테스트, 기존 결제·예매 통합 테스트
- [ ] **2-4 장애**: 브로커 중단·복구 후 미완료 이벤트 재전달, runbook §3.7 보강(DLT 포함)
  - 완료 기준: 브로커 중단 중 발행한 이벤트가 `event_publication`에 남고 복구·재시작 후 소비된다
  - 검증: 장애 주입 테스트(Testcontainers 컨테이너 중지·재기동)
- [ ] **2-5 회귀·측정**: 전체 테스트·check·E2E 13개, Gatling `hot-seats` 콜드 3회로 확정 지연 p95를 [01 기준선](../performance/m6/01-pre-split-baseline-gatling.md)과 비교해 `docs/performance/m6/` 아래 02번 문서로 기록. `scripts/e2e/run.sh`·`scripts/loadtest/run.sh`·`scripts/dev/run.sh`의 Kafka 사전 조건 반영
  - 완료 기준: 2단계 완료 기준 전부. `serialize-externalization`·outbox 채택 여부 확정
  - 검증: `./scripts/agent/test/run-tests.sh`, `check-summary.sh`, `./scripts/e2e/run.sh`, Gatling(단독 실행)
- [ ] **2-6 문서**: architecture.md(§3 구성도·기술 스택), events.md(외부화 구현 반영·"구현 전" 문구 제거), AGENTS.md(§6 명령·§7 사전 조건), 필요 시 ADR(직렬화·실패 처리), 이 문서 갱신
  - 검증: `./scripts/agent/docs/check-docs.sh`

각 단계의 세부 체크리스트는 단계를 시작할 때 이 문서에 추가한다. 한 단계의 완료 기준을 만족하기 전에는 다음 단계를 시작하지 않는다.

## 4. 위험과 대응

| 위험 | 대응 |
|---|---|
| 분리 중 이벤트 유실·중복 | 소비자 멱등, 미완료 이벤트 관측(P5), 최종 방어선은 DB 제약(결제·웹훅 멱등 키 유지) |
| DB 이전 중 데이터 불일치 | 점검 창과 대조 절차([ADR 0040](../adr/0040-payment-separate-database-same-instance.md)) |
| 로컬 자원 부족(Kafka·앱 증가) | 단일 브로커 KRaft로 시작한다. Kafka는 결제 흐름의 필수 인프라이므로 compose 기본 서비스로 둔다([ADR 0043](../adr/0043-kafka-externalization-step2-decisions.md)) |
| E2E 시간 증가 | 사전 작업의 CI에서 단계별 분리 실행 |

## 5. 열린 결정과 ADR

| 결정 | 선택 | 링크 |
|---|---|---|
| Kafka 도입 범위 | Modulith 이벤트 외부화만. 좌석 스트림은 Redis Streams 유지 | [ADR 0039](../adr/0039-kafka-externalizes-modulith-events-only.md) |
| payment DB 분리 방식 | 같은 인스턴스의 별도 DB. 점검 창으로 이전 | [ADR 0040](../adr/0040-payment-separate-database-same-instance.md) |
| 인증 구조 | 유지. SAS는 M6 후 별도 ADR | [ADR 0041](../adr/0041-auth-stays-in-core-sas-deferred.md) |

미결 세부 사항(해당 단계에서 정한다): `serialize-externalization`·outbox 모드 최종 확정(2-5 측정 후, 잠정 미채택은 [ADR 0043](../adr/0043-kafka-externalization-step2-decisions.md)), 결제 대사 Batch의 실행 구성, payment 서비스의 JWT 검증 방식(3단계), 웹훅 경로의 payment 앱 직접 노출 방식(3단계), 토픽당 파티션 수(3으로 시작, 2-5 측정 후 확정). 토픽 단위(이벤트별)·명명 규칙, 로컬 리스너 제거 방식, 본문 직렬화, 단일 브로커는 ADR 0043에서 확정.

## 6. 문서 반영 계획

구현 단계에서 함께 고친다. 지금은 바꾸지 않는다.

- `docs/architecture.md` §3 구성도·§3 배포 단위 표(payment·settlement 추가), §4 모듈 표
- `docs/architecture/events.md` 이벤트 종류 표(Kafka 추가), `data-ownership.md`·`erd.md`(payment DB)
- `AGENTS.md` §2 저장소 구조·§6 명령(새 모듈·이미지·k8s)
