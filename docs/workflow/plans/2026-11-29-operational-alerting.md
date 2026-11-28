---
status: done
approved_at: 2026-11-29T00:58:42+09:00
---

# 운영 알림과 비동기 흐름 관측

## 목표와 근거

로드맵 [M6 본작업](../../roadmap.md#본-작업)의 "운영 알림과 비동기 흐름 관측" 항목이다. 결정은 [ADR 0055](../../adr/0055-operational-alerting-and-async-flow-observability.md)를 따르고, 기존 관측 스택은 [ADR 0024](../../adr/0024-observability-and-load-test.md)를 따른다. 컨테이너 이미지·k8s 매니페스트 전에 Compose 환경에서 완성하고, 같은 rule·대시보드를 k8s에서 재사용한다.

끝났을 때 관찰할 결과:

- Prometheus가 settlement를 포함한 모든 상시 앱과 kafka-exporter를 수집한다.
- 컨슈머 앱을 끄면 Alertmanager UI에 앱 중단 알림이 뜨고, 이어서 컨슈머 lag 알림이 뜬다. 환경 변수로 webhook URL을 주면 그 주소로도 전달된다.
- Batch Job이 `FAILED`로 끝나면 실패 알림이 켜지고, 같은 Job이 다시 성공하면 꺼진다.
- Grafana의 `Ticketing Overview`(`ticketing-overview`)는 운영 화면이고 모든 알림에 대응하는 패널이 있다. 기존 부하 측정 패널은 `Ticketing Load Test`(`ticketing-loadtest`)에서 그대로 본다.
- 알림 규칙·Alertmanager 설정·대시보드는 로컬 `verify-changed.sh`와 CI에서 검사된다.

## 범위

- 포함: `observability/`(Prometheus 설정·rule·rule 테스트, Alertmanager 설정, Grafana 대시보드), `compose.observability.yaml`, `compose.yaml`의 Kafka 내부 리스너, `common`의 Batch 상태 지표, core·payment·settlement 설정과 테스트, 관측 검사 스크립트와 `verify-changed.sh` 분기, CI 잡, 런북·아키텍처 문서.
- 범위 밖: 로그 수집(Loki), web 계측, 지연(p95) 알림, 예정 실행 누락 알림(k8s CronJob 단계), Slack 전용 수신기(`slack_configs`), k8s용 `PrometheusRule`·매니페스트, `loadtest/` 러너와 Gatling 대시보드 변경, 과거 성능 기록 수정, 앱 HTTP API·Kafka 계약 변경.

## task

### T1. settlement 지표 수집

- [x] 완료
- Blocked by: 없음
- 변경 범위와 제약: `observability/prometheus/prometheus.yml`의 `apps` job에 `host.docker.internal:18086`을 추가한다. settlement DB에는 `event_publication` 테이블이 없으므로 `settlement/src/main/resources/application.yml`에서 `ticketing.event-publication.metrics.enabled=false`를 설정한다. 이 설정이 없으면 settlement를 수집할 때마다 조회 실패 경고가 남는다. 다른 앱의 설정은 바꾸지 않는다.
- 테스트 경계와 선례: settlement의 `/actuator/prometheus` 공개 경계. `core/.../ActuatorPrometheusTests.kt`를 선례로 settlement에 `ActuatorPrometheusTests`를 추가한다. `SettlementWebTestSupport`를 쓰고 인증 없이 200, `application="settlement"`, `hikaricp_connections` 포함, `ticketing_event_publication` 미포함을 확인한다. `prometheus.yml` 문법은 `promtool check config`로 확인한다.
- AC:

  ```bash
  ./scripts/agent/test/run-tests.sh :settlement:test --tests '*ActuatorPrometheusTests'
  docker run --rm --entrypoint promtool -v "$PWD/observability/prometheus:/cfg:ro" prom/prometheus:v3.5.0 check config /cfg/prometheus.yml
  ```

### T2. kafka-exporter와 Kafka 내부 리스너

- [x] 완료
- Blocked by: 없음
- 변경 범위와 제약: 지금 Kafka는 광고 주소가 `localhost:19092`뿐이라 Compose 네트워크 안의 exporter가 브로커에 붙을 수 없다. `compose.yaml`의 Kafka에 내부 리스너(예: `INTERNAL://kafka:29092`)를 추가하고, `KAFKA_LISTENERS`, `KAFKA_ADVERTISED_LISTENERS`, `KAFKA_LISTENER_SECURITY_PROTOCOL_MAP`, `KAFKA_INTER_BROKER_LISTENER_NAME`을 맞춘다. 호스트 접속(`localhost:${TICKETING_KAFKA_PORT}`)과 healthcheck는 그대로 둔다. `compose.observability.yaml`에 버전을 고정한 kafka-exporter(`danielqsj/kafka-exporter`)를 추가하고 내부 리스너로 연결한다. 포트는 호스트에 열지 않는다. `prometheus.yml`에 `kafka` job을 추가한다. 컨슈머 그룹 lag(`kafka_consumergroup_lag`)과 `.dlt` 토픽 오프셋(`kafka_topic_partition_current_offset`)이 수집되는지 확인한다.
- 테스트 경계와 선례: Compose 스택과 Prometheus HTTP API가 경계다. 앱 동작은 바뀌지 않으므로 기존 Kafka 통합 테스트(`KafkaRecoveryIntegrationTests`, Testcontainers)는 영향이 없다. 호스트 접속은 E2E(`scripts/e2e/run.sh`)가 쓰므로 PR CI의 backend E2E에서 확인한다.
- AC:

  ```bash
  docker compose -f compose.yaml -f compose.observability.yaml config -q
  docker compose -f compose.yaml -f compose.observability.yaml up -d --wait kafka kafka-exporter prometheus
  # 기대: kafka job의 up 값이 1이고, ticketing 토픽과 .dlt 토픽의 current_offset 시계열이 있다
  curl -s 'http://localhost:19090/api/v1/query?query=up%7Bjob%3D%22kafka%22%7D'
  curl -s 'http://localhost:19090/api/v1/query?query=count%20by%20(topic)%20(kafka_topic_partition_current_offset%7Btopic%3D~%22ticketing.*%22%7D)'
  docker compose -f compose.yaml -f compose.observability.yaml down
  ```

### T3. Batch Job 상태 지표

- [x] 완료
- Blocked by: 없음
- 변경 범위와 제약: `common`에 `EventPublicationMetrics`와 같은 형태의 자동 구성과 지표 클래스를 추가하고 `AutoConfiguration.imports`에 등록한다. 조건은 `DSLContext`·`MeterRegistry` 빈과 `ticketing.batch.metrics.jobs`(Job 이름 목록, 비어 있으면 빈 없음)다. `batch.batch_job_instance`·`batch.batch_job_execution`을 jOOQ 이름 참조로 읽고 10초 캐시를 쓴다. 지표는 Job별 두 개다.
  - 실패 여부 gauge(1/0): 가장 최근 **완료** 실행(`end_time`이 있는 실행)이 `FAILED`·`ABANDONED`이면 1이고, `COMPLETED`·`STOPPED`이면 0이다. 실행 중인 실행은 건너뛴다.
  - 마지막 성공 시각 gauge(epoch 초): 마지막 `COMPLETED` 실행의 `end_time`이다.
  - 완료된 실행이 없는 Job은 시계열을 내보내지 않는다. 조회 실패 시 직전 값을 쓴다.

  지표 이름은 `ticketing.batch.job.failed`와 `ticketing.batch.job.last.success`(태그 `job`)로 한다. 감시 목록은 각 앱의 `application.yml`에 둔다.
  - core: `expirePendingBookingsJob`, `reconcileSeatSnapshotJob`, `releaseStaleCouponReservationsJob`
  - payment: `paymentReconciliationJob`
  - settlement: `dailySettlementJob`

  분리 전 정산 이력이 core DB에 남아 있으므로 core 목록에 `dailySettlementJob`을 넣지 않는다. 같은 DB를 쓰는 batch 프로필(`application-batch.yml` 3개)은 목록을 비워 중복 수집을 끈다.
- 테스트 경계와 선례: `/actuator/prometheus`와 `MeterRegistry`가 공개 경계다. core에 `EventPublicationMetricsTests`를 선례로 `BatchJobMetricsTests`(Testcontainers, `MutableClock`)를 추가하고, `batch` 스키마에 실행 행을 직접 넣어 다음을 확인한다.
  - 최근 완료 실행이 `COMPLETED`이면 0, `FAILED`이면 1, `ABANDONED`이면 1, `STOPPED`이면 0
  - `FAILED` 뒤 `COMPLETED`이면 0으로 해소되고 마지막 성공 시각이 갱신됨
  - `FAILED` 뒤 실행 중 실행만 있으면 1 유지
  - 이력 없는 Job은 시계열 없음
  - 목록 밖 Job(`dailySettlementJob`)은 시계열 없음
  - batch 프로필에서 빈 없음

  payment(`PaymentApplicationTests` 계열)와 settlement(T1의 `ActuatorPrometheusTests`)에는 자기 Job의 `COMPLETED` 실행 1건을 넣고 `/actuator/prometheus`에 `ticketing_batch_job_failed{job="<Job>"}`이 나오는지만 확인한다.
- AC:

  ```bash
  ./scripts/agent/test/run-tests.sh :core:test --tests '*BatchJobMetricsTests' --tests '*EventPublicationMetricsTests' --tests '*ActuatorPrometheusTests'
  ./scripts/agent/test/run-tests.sh :payment:test --tests '*BatchJobMetrics*' --tests '*PaymentBatchContextTests'
  ./scripts/agent/test/run-tests.sh :settlement:test --tests '*ActuatorPrometheusTests'
  ```

### T4. 알림 규칙, Alertmanager, 관측 설정 검사

- [x] 완료
- Blocked by: T1, T2, T3
- 변경 범위와 제약:
  - `observability/prometheus/rules/`에 ADR 0055 결정 4의 알림 7종을 둔다. 대상은 `AppDown`, `HighErrorRate`, `EventPublicationStuck`, `KafkaConsumerLagHigh`, `KafkaDeadLetterIncreased`, `BatchJobFailed`, `HikariPoolExhausted`이고, 기준값은 인터뷰 Q8의 잠정값이다.
  - 각 규칙에는 `severity` 라벨과 `summary`·`runbook`(런북 절 앵커) 주석을 달고, 운영 Overview의 대응 패널 제목을 `panel` 주석으로 단다.
  - `observability/prometheus/rules/tests/`에 규칙별 발화·비발화 `promtool test rules` 테스트를 둔다.
  - `prometheus.yml`에 `rule_files`와 `alerting`을 추가한다.
  - `compose.observability.yaml`에 버전을 고정한 `prom/alertmanager`를 `127.0.0.1:19093`으로 추가한다.
  - Alertmanager 설정은 환경 변수 `ALERTMANAGER_WEBHOOK_URL`이 있을 때만 `webhook_configs` 수신기를 쓰고, 없으면 수신기 없이 UI로만 본다. Alertmanager는 설정 파일의 환경 변수를 펼치지 않으므로 컨테이너 시작 시 템플릿에서 설정을 만드는 방식을 쓴다. 비밀 값은 저장소에 넣지 않는다.
  - Grafana에 Alertmanager datasource를 프로비저닝한다.
  - `scripts/observability/check.sh`를 추가한다. Docker 이미지로 `promtool check config`, `promtool check rules`, `promtool test rules`, `amtool check-config`(두 경우 모두)를 실행하고, 대시보드 JSON의 파싱과 uid 중복을 확인한다.
  - `scripts/agent/test/verify-changed.sh`에 `observability/*`·`compose.observability.yaml` 변경 시 이 스크립트를 실행하는 분기를 추가한다.
  - `.github/workflows/ci.yml`에 `observability` 잡을 추가한다.
- 테스트 경계와 선례: rule 파일의 공개 경계는 Prometheus가 평가하는 표현식이다. `promtool test rules`로 규칙마다 최소 1개의 발화 사례와 1개의 비발화 사례를 둔다(예: `STOPPED` Batch는 비발화, 부하 측정 중 정상 409·429는 `HighErrorRate` 비발화). 스크립트 문법은 `verify-changed.sh`의 shellcheck가 본다. 스택 기동 후 실제 발화는 T6의 런북 수동 절차로 확인한다.
- AC:

  ```bash
  ./scripts/observability/check.sh
  ./scripts/agent/test/verify-changed.sh --dry-run   # 기대: observability 검사가 선택된다
  ```

### T5. 대시보드 재편

- [x] 완료
- Blocked by: T4
- 변경 범위와 제약:
  - `observability/grafana/dashboards/ticketing-overview.json`의 내용을 `ticketing-loadtest.json`으로 옮기고 uid를 `ticketing-loadtest`로, 제목을 `Ticketing Load Test`로 바꾼다. 패널과 쿼리는 바꾸지 않는다.
  - `ticketing-overview.json`(uid `ticketing-overview`, 제목 `Ticketing Overview`)은 운영 대시보드로 새로 만든다. 인터뷰 Q16의 다섯 행(상태 요약, 트래픽, 비동기 흐름, Batch, 자원 요약)으로 구성하고 `application` 변수를 둔다.
  - 운영 대시보드에는 k6·Gatling·JVM 상세 패널을 넣지 않고, Load Test 대시보드로 가는 링크를 둔다.
  - `Ticketing · Gatling hot-seats 300ms`는 바꾸지 않는다.
  - `scripts/observability/check.sh`에 두 가지 검사를 추가한다. 모든 알림의 `panel` 주석이 운영 Overview의 패널 제목과 일치하는지 확인하고, Load Test 대시보드의 패널 쿼리가 이동 전과 같은지(패널 제목·expr 목록 비교) 확인한다.
- 테스트 경계와 선례: 대시보드 JSON과 Grafana 프로비저닝이 경계다. 알림-패널 대응과 Load Test 보존은 검사 스크립트로 확인하고, 화면은 Grafana를 띄워 두 대시보드가 프로비저닝되는지 API로 확인한다.
- AC:

  ```bash
  ./scripts/observability/check.sh
  docker compose -f compose.yaml -f compose.observability.yaml up -d --wait grafana
  # 기대: ticketing-overview, ticketing-loadtest, ticketing-hot-seats-gatling 세 uid가 모두 나온다
  curl -s 'http://localhost:13001/api/search?type=dash-db' | python3 -c 'import json,sys; print(sorted(d["uid"] for d in json.load(sys.stdin)))'
  docker compose -f compose.yaml -f compose.observability.yaml down
  ```

### T6. 런북과 설계 문서

- [x] 완료
- Blocked by: T5
- 변경 범위와 제약:
  - `docs/runbook.md` 3.7을 고친다. 미완료 이벤트 지표는 core와 payment가 수집하고 settlement는 해당 테이블이 없다. 패널 위치는 운영 Overview다.
  - 런북 3장에 "알림이 울렸다" 절을 추가하고, 알림별 의미·먼저 볼 패널·대응 절(기존 3.2, 3.7, 4절 등)을 표로 적는다. rule의 `runbook` 주석 앵커와 일치시킨다.
  - 런북 8절의 도구 표에 Alertmanager(19093)와 대시보드 세 개를 추가한다. `ALERTMANAGER_WEBHOOK_URL` 사용법, "M6 이전 측정 기록의 `Ticketing Overview`는 지금의 `Ticketing Load Test`다"라는 한 줄, 알림 발화 확인 수동 절차(payment를 끄고 `AppDown`과 lag 알림 확인, 실패하는 Batch 실행으로 `BatchJobFailed` 확인과 재실행으로 해소 확인)를 넣는다.
  - `docs/architecture.md`의 관측 항목과 `compose.observability.yaml` 상단 주석을 구현에 맞게 고친다.
  - `scripts/observability/check.sh`에 rule의 `runbook` 주석 앵커가 `docs/runbook.md`에 있는지 확인하는 검사를 추가한다.
  - 로드맵 항목에 완료를 표시한다.
- 테스트 경계와 선례: 문서 링크·앵커와 rule 주석의 런북 앵커가 경계다.
- AC:

  ```bash
  ./scripts/agent/docs/check-docs.sh docs/runbook.md docs/architecture.md docs/roadmap.md
  ./scripts/observability/check.sh   # 기대: runbook 앵커 검사 포함 통과
  ```

## 최종 검증

- 시작 SHA부터 HEAD까지 `./scripts/agent/test/verify-changed.sh <시작 SHA>`를 실행한다.
- JVM 변경(common·core·payment·settlement)이 있으므로 `./scripts/agent/test/run-tests.sh`를 필터 없이 실행한다.
- `./scripts/observability/check.sh`를 실행한다.
- PR의 CI에서 backend E2E(Kafka 내부 리스너 추가 후 호스트 접속 유지)와 새 `observability` 잡 결과를 확인한다. 로컬 E2E는 실행하지 않는다.
