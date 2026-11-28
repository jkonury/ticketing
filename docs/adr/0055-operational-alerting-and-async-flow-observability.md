# ADR 0055: 운영 알림과 비동기 흐름 관측

- 상태: 합의
- 작성일: 2026-11-29
- 합의일: 2026-11-29
- 결정 주체: 2026-11-29 모니터링 보완 설계 인터뷰(Q1~Q17)
- 대체: [ADR 0024](0024-observability-and-load-test.md)의 결정 6 일부(관측 스택 구성)
- 대체됨·일부 변경: 없음
- 관련: [ADR 0024](0024-observability-and-load-test.md), [ADR 0039](0039-kafka-externalizes-modulith-events-only.md), [ADR 0043](0043-kafka-externalization-step2-decisions.md), [ADR 0051](0051-settlement-database-cutover.md), [설계](../architecture.md), [로드맵](../roadmap.md)

## 배경

관측 스택(ADR 0024)은 M5 부하 측정에서 병목을 찾으려고 만들었다. 대시보드를 사람이 보고 있을 때만 쓸모가 있고, 알림이 없다.

M6에서 payment와 settlement를 분리하고 Kafka를 들였다. 그 뒤로 실패가 요청 응답에 드러나지 않고 조용히 쌓이는 경로가 생겼다. 소비가 멈춰 컨슈머 lag가 쌓이는 경우, 재시도를 소진한 레코드가 `<토픽>.dlt`에 적재되는 경우, `event_publication`에 미완료 발행이 남는 경우, Batch Job이 실패한 경우다. 지금은 이를 알아챌 수단이 없다. settlement 앱(18086)은 Prometheus 수집 대상에서도 빠져 있다.

M6 완료 기준은 "k8s 클러스터에서 전체 시나리오와 Batch가 동작한다"이다. 동작 여부를 판단할 수단을 k8s 전환 전에 Compose 환경에서 갖추고, 같은 설정을 k8s에서 재사용하려고 지금 정한다.

제약: 단일 개발 머신과 CI에서 쓰며 알림을 받을 고정 수신처가 없다. 비밀 값은 저장소에 넣지 않는다. Batch는 Compose에서 수동 실행하고 k8s에서 CronJob이 된다.

## 결정

1. 관측 스택의 목적에 운영 관측을 더한다. 알림, 비동기 흐름(Kafka·이벤트 발행 레지스트리), Batch 실행 감시가 범위다. 로그 수집(Loki), web 계측, 지연(p95) 알림은 범위 밖이다(ADR 0024 결정 6의 로그 수집 제외는 유지).
2. 알림은 저장소의 Prometheus alerting rule 파일로 정의하고 Alertmanager로 전달한다. 관측 스택에 Alertmanager를 추가한다.
3. 알림 수신처는 실행 환경이 정한다. 기본은 수신처 없이 Alertmanager UI와 Grafana에서 확인한다. 환경 변수로 webhook URL을 주면 그쪽으로도 보낸다. k8s에서는 같은 값을 Secret으로 넣는다.
4. 초기 알림은 앱 중단, 앱별 5xx 비율, 미완료 이벤트 발행 정체, 컨슈머 lag, DLT 적재 증가, Batch 실패, Hikari 연결 대기다. 기준값은 잠정이며 rule 파일이 원본이다. 지연 알림은 두지 않는다. 성능 목표는 k6 thresholds가 검사한다.
5. 컨슈머 lag과 DLT 적재량은 브로커 기준 지표로 본다. 관측 스택에 kafka-exporter를 추가한다. 앱의 `ticketing.kafka.dead.letter` 카운터는 토픽별 원인을 보는 패널로 쓰고, 알림 근거로 쓰지 않는다.
6. Batch 실행 상태는 상시 앱(core·payment·settlement)이 자기 DB의 `batch` 스키마를 읽어 gauge로 노출한다. `common`의 `EventPublicationMetrics`와 같은 방식(캐시, batch 프로필에서 중복 수집 끔)을 쓴다. 앱마다 감시할 Job 이름을 설정으로 정하고, 목록에 없는 Job은 노출하지 않는다.
7. Batch 실패는 Job별 가장 최근 완료 실행의 상태로 판단한다. `FAILED`와 `ABANDONED`는 실패이고 `STOPPED`는 실패가 아니다. 실행 중인 Job은 직전 완료 실행으로 판단한다. 실패 상태는 같은 Job이 다시 성공해야 해소된다. 실행 이력이 없는 Job은 지표를 내보내지 않는다. 예정 실행을 놓친 경우의 알림은 k8s CronJob 단계에서 정한다.
8. 대시보드를 용도별로 나눈다. uid `ticketing-overview`(`Ticketing Overview`)는 운영 대시보드로 새로 구성한다. 기존 부하 측정용 패널은 내용을 바꾸지 않고 `Ticketing Load Test`(uid `ticketing-loadtest`)로 옮긴다. 운영 대시보드의 패널은 모든 알림에 대응해야 하고, k6·Gatling·JVM 상세 패널은 넣지 않는다.
9. 과거 성능 측정 기록의 대시보드 이름은 고치지 않는다. 런북에 이름이 바뀌었다는 사실을 남긴다.

## 검토한 대안

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| Grafana alerting(프로비저닝 YAML) | 형식이 장황하고 Grafana에 묶인다. rule 파일은 `promtool`로 CI에서 단위 테스트할 수 있고, k8s에서 `PrometheusRule`로 같은 표현식을 쓴다 |
| 처음부터 Slack 연동 | 로컬·CI에 고정 수신처가 없고, 비밀 값을 설정에 넣어야 한다 |
| 앱 측 Kafka 클라이언트 lag 지표만 사용 | 컨슈머 앱이 죽으면 지표도 사라진다. 가장 중요한 경우를 놓친다. 앱 DLT 카운터는 재시작하면 0이 된다 |
| Pushgateway로 Batch 결과 push | 컴포넌트가 늘고, 마지막 push 값이 계속 남아 낡은 상태를 보여 준다 |
| Batch 감시를 k8s 단계로 미루고 kube-state-metrics 사용 | Compose 환경에서 감시 수단이 없다. Job 상태가 아닌 Pod 종료 코드만 본다 |
| 앱 `batch` 스키마의 모든 Job 노출 | core DB에 분리 전 `dailySettlementJob` 이력이 남아 있어(ADR 0051), 옛 실패가 알림을 계속 켠다 |
| 운영용 `Ticketing Pipeline` 대시보드를 따로 추가 | 운영 화면이 둘이면 어디부터 볼지 다시 정해야 한다. `ticketing-overview`가 운영 화면이 되는 편이 북마크·런북 링크와 맞다 |
| 지연(p95) 알림 | 단일 머신 부하 측정 때마다 울려 소음이 된다 |

## 결과

- 얻는 것: 비동기 흐름이 막히거나 Batch가 실패하면 대시보드를 보고 있지 않아도 알 수 있다. 알림 규칙을 CI에서 검사한다. rule·exporter·대시보드를 k8s 전환에서 재사용한다.
- 감수하는 것: 관측 스택에 컨테이너가 둘(Alertmanager, kafka-exporter) 늘어난다. 상시 앱이 `batch` 스키마를 주기적으로 조회한다. 기준값은 잠정이라 부하 측정 중 오탐이 날 수 있다. 로그 수집이 없으므로 원인은 trace와 앱 로그 파일로 찾는다. 기존 북마크 `ticketing-overview`는 부하 측정 패널이 아니라 운영 화면을 연다.
- 바뀌는 문서·지침: `docs/architecture.md`(부하 테스트·관측 항목), [ADR 0024](0024-observability-and-load-test.md)의 "대체됨·일부 변경" 필드, `docs/runbook.md` 3.7·8절, `docs/roadmap.md` M6 본작업

## 검증

- 알림 규칙: CI에서 `promtool check rules`와 `promtool test rules`로 규칙별 발화·비발화를 검사한다.
- Batch gauge: Testcontainers 통합 테스트가 Job 상태별(`COMPLETED`·`FAILED`·`ABANDONED`·`STOPPED`·실행 중·이력 없음) 노출 값과 감시 목록 밖 Job 제외를 검사한다.
- 스택 전체: 런북의 알림 발화 확인 절차(예: payment를 끄고 앱 중단·lag 알림 확인)를 수동으로 실행한다. 자동 스택 E2E는 두지 않는다.

## 변경 이력

- 2026-11-29: 작성(제안), 합의
