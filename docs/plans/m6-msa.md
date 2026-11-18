# M6 MSA + k8s 계획

- 작성일: 2026-11-18
- 상태: 초안. 열린 결정 3건은 합의 완료(ADR 0039~0041). 구현 미착수
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
- E2E는 `core/src/test/.../e2e` 7개(예매·동시성·결제 실패·웹훅 멱등·만료 후 환불)와 `e2e/` 6개(대기열·입장 토큰·SSE·쿠폰 Saga 3종)다. Batch(정산·대사·미결제 정리) E2E는 없다.

## 2. 사전 작업

M6 본 작업 전에 끝낸다. 로드맵의 "M6 사전 작업" 체크리스트와 같은 항목이다.

| 번호 | 항목 | 이유 |
|---|---|---|
| P1 | CI 파이프라인(GitHub Actions): `build`(ktlint·detekt 포함), web 검사, 문서 검사 | 분리 후 모듈이 늘어 로컬 검사만으로는 회귀를 놓친다 |
| P2 | Batch E2E 기준선: 정산·대사·미결제 정리 Job을 실제 DB로 실행해 결과를 고정 | 분리 전후 결과를 비교할 기준이 없다 |
| P3 | 공용 클래스를 `common` Gradle 모듈로 추출 | payment가 core 소스를 참조하지 않고 독립 빌드되게 한다 |
| P4 | 계약 이벤트를 `contract` 모듈로 추출하고 직렬화 형식 고정(JSON, 버전 필드) | 프로세스 경계를 넘는 페이로드는 호환성 규칙이 필요하다([events.md §1](../architecture/events.md#1-공통-규칙)) |
| P5 | `event_publication` 미완료 이벤트 관측(건수·최고령 지표)과 재처리 runbook | Kafka 외부화 후 전송 실패는 이 테이블에 쌓인다 |
| P6 | (선택) 분리 전 성능 기준선 1회 측정 | 분리로 늘어나는 네트워크 홉의 비용을 비교한다 |

P3·P4는 모듈 의존이 바뀌므로 Modulith 구조 검증(`ApplicationModules.verify()`)과 ArchUnit을 반드시 통과시킨다.

## 3. 본 작업 순서와 완료 기준

| 단계 | 내용 | 완료 기준 | 검증 방법 |
|---|---|---|---|
| 1 | contract·common 추출 마무리(P3·P4 결과 반영), 이벤트별 외부화 대상 표 확정 | core 빌드와 기존 테스트 통과. 이벤트 JSON 스키마 예시가 문서에 있다 | `./scripts/agent/test/run-tests.sh`, Modulith 검증 |
| 2 | `payment` 분리: 별도 Gradle 앱, 별도 DB 이전([ADR 0040](../adr/0040-payment-separate-database-same-instance.md)). 이 단계는 이벤트를 아직 Modulith 인프로세스로 연결할 수 없으므로 3단계와 한 묶음으로 배포한다 | payment 앱이 독립 기동하고 자기 DB와 `event_publication`을 쓴다. 이전 후 건수·체크섬 일치 | 이전 절차 체크, 결제 E2E |
| 3 | Kafka 외부화([ADR 0039](../adr/0039-kafka-externalizes-modulith-events-only.md)): `@Externalized`, 소비자 멱등, `serialize-externalization`·outbox 모드 검토 | `PaymentRequested`·`RefundRequested`와 결제 결과 3종이 Kafka로 오가고 같은 이벤트를 두 번 받아도 결과가 같다. 브로커 중단·복구 후 미완료 이벤트가 재전달된다 | 소비자 멱등 테스트, 브로커 장애 주입, Testcontainers Kafka |
| 4 | `settlement` 분리: `BookingConfirmed`·`BookingCancelled` 소비, 정산 Batch 구성 | 분리 후 정산 결과가 P2 기준선과 같다 | Batch E2E 비교 |
| 5 | 예매–결제·쿠폰 Saga 재검증: 결제 실패·웹훅 중복·만료 후 환불·쿠폰 예약 해제·사용 확정 | 기존 E2E 13개가 분리 환경에서 통과하고, 브로커·앱 중단 시나리오에서 보상 흐름이 완료된다 | `./scripts/e2e/run.sh` 확장, 장애 주입 |
| 6 | 컨테이너 이미지: 모듈별 Dockerfile, 이미지 빌드 | 모든 앱 이미지가 빌드되고 compose로 기동한다 | compose 기동 + E2E |
| 7 | k8s 매니페스트: Deployment, Service, Ingress, Batch CronJob, Kafka·PostgreSQL·Redis 구성 | 클러스터에서 전체 시나리오와 Batch가 동작한다 | 로컬 클러스터(kind 또는 minikube)에서 E2E, CronJob 수동 실행 |
| 8 | realtime HPA 검증 | 부하에서 replica가 늘고 SSE 연결이 유지되며 입장 순서가 깨지지 않는다 | k6 `queue-surge`, 메트릭 확인 |

각 단계의 세부 체크리스트는 단계를 시작할 때 이 문서에 추가한다. 한 단계의 완료 기준을 만족하기 전에는 다음 단계를 시작하지 않는다.

## 4. 위험과 대응

| 위험 | 대응 |
|---|---|
| 분리 중 이벤트 유실·중복 | 소비자 멱등, 미완료 이벤트 관측(P5), 최종 방어선은 DB 제약(결제·웹훅 멱등 키 유지) |
| DB 이전 중 데이터 불일치 | 점검 창과 대조 절차([ADR 0040](../adr/0040-payment-separate-database-same-instance.md)) |
| 로컬 자원 부족(Kafka·앱 증가) | 단일 브로커 KRaft로 시작하고 compose 프로필로 분리 |
| E2E 시간 증가 | 사전 작업의 CI에서 단계별 분리 실행 |

## 5. 열린 결정과 ADR

| 결정 | 선택 | 링크 |
|---|---|---|
| Kafka 도입 범위 | Modulith 이벤트 외부화만. 좌석 스트림은 Redis Streams 유지 | [ADR 0039](../adr/0039-kafka-externalizes-modulith-events-only.md) |
| payment DB 분리 방식 | 같은 인스턴스의 별도 DB. 점검 창으로 이전 | [ADR 0040](../adr/0040-payment-separate-database-same-instance.md) |
| 인증 구조 | 유지. SAS는 M6 후 별도 ADR | [ADR 0041](../adr/0041-auth-stays-in-core-sas-deferred.md) |

미결 세부 사항(해당 단계에서 정한다): `serialize-externalization`·outbox 모드 채택, 결제 대사 Batch의 실행 구성, payment 서비스의 JWT 검증 방식, 단일 브로커 vs 3대.

## 6. 문서 반영 계획

구현 단계에서 함께 고친다. 지금은 바꾸지 않는다.

- `docs/architecture.md` §3 구성도·§3 배포 단위 표(payment·settlement 추가), §4 모듈 표
- `docs/architecture/events.md` 이벤트 종류 표(Kafka 추가), `data-ownership.md`·`erd.md`(payment DB)
- `AGENTS.md` §2 저장소 구조·§6 명령(새 모듈·이미지·k8s)
