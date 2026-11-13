# Phase M5-1: 관측과 기준 측정

- 기준: `docs/roadmap.md § M5. 부하 테스트와 관측`
- 목표: 관측 스택(지표·분산 추적)을 붙이고, k6 시나리오 두 개로 기준 수치를 측정한다. 측정 결과에서 병목 후보를 뽑는다.
- 범위 밖: 병목 개선과 전후 비교는 **M5-2**에서 한다. M5-2 step은 이 phase의 기준 측정 결과(`docs/performance/m5-baseline.md`)를 보고 사용자와 다시 논의해 작성한다.
- 완료 기준: 두 시나리오의 기준 수치(3회 중앙값)와 실패 주입 1회 결과가 문서에 있고, 모든 실행에서 중복 판매가 0건이다.
- 각 step은 독립 세션에서 순서대로 실행한다. 앞 step의 AC가 통과해야 다음 step을 시작한다.

## 합의된 결정 (2026-11-12)

| 주제 | 결정 |
|---|---|
| 순서 | M5를 먼저 한다. `docs/plans/backend-guideline-adoption.md`는 M5 뒤로 미룬다. 측정 전후 비교에 다른 리팩터링이 섞이지 않게 하기 위해서다 |
| 부하 경로 | k6가 Gateway(8000)를 직접 호출한다. web BFF는 측정 범위 밖이다 |
| 인증 | 부하 테스트용 회원 1만 명은 SQL 시드로 만든다. k6가 로컬 `.env`의 `TICKETING_JWT_SECRET`으로 HS256 access 토큰을 직접 서명한다(클레임 `sub`=회원 ID, `role`, `iat`, `exp`). 로그인(bcrypt)은 부하에 넣지 않는다 |
| SSE | k6에 SSE가 없으므로 xk6-sse 확장을 넣은 k6 바이너리를 Docker `grafana/xk6`로 빌드한다. 산출물은 `build/loadtest/k6` |
| 지표 | Micrometer + Prometheus registry. 모든 Spring 앱이 `/actuator/prometheus`를 노출한다 |
| 추적 | Micrometer Tracing + OpenTelemetry 브리지 → OTLP(HTTP) → Grafana Tempo. Grafana에서 Prometheus exemplar로 trace와 연결한다. web(Next) 계측은 범위 밖 |
| 관측 스택 | `compose.observability.yaml`(Prometheus, Tempo, Grafana). Prometheus는 remote-write 수신을 켜서 k6 지표도 받는다. Grafana는 3001 포트(3000은 web). datasource와 대시보드는 저장소에 프로비저닝한다. 로그 수집(Loki)은 하지 않는다 |
| 시나리오 A `queue-surge` | 1만 명이 대기열 등록 → 순번 SSE → 입장 → 좌석 SSE 스냅샷 → 빈 좌석 선점(1~4매) → 결제 → 확정 폴링. 회차 좌석 약 5,000석이므로 매진 경합이 생긴다. 입장 설정은 기본값(초당 50명) |
| 시나리오 B `hot-seats` | 1,000명. 실행 시 realtime `admission-batch-size`를 1000으로 올려 모두 입장시킨 뒤 같은 시각에 앞줄 100석을 놓고 1~4매씩 선점을 시도한다. 성공한 사람은 결제까지 진행한다 |
| 결제 | 기준 측정은 fake-pg 실패율 0%. 별도 1회는 시나리오 B에서 실패 10%와 웹훅 중복 30%를 켜서(좌석 경합과 보상이 함께 일어나므로) 부하 중 멱등성·보상을 확인한다 |
| 합격 기준(잠정) | 예매 생성 p95 < 300ms, 예상한 응답(409 좌석 경합, 429 rate limit)을 뺀 오류율 < 1%, SSE 연결 성공률 > 99.9%. 중복 판매 0건은 절대 기준이다. 기준 측정에서 못 미치는 항목이 M5-2의 개선 대상이 된다 |
| 실행 환경 | postgres·redis는 Compose로 띄운다. core·realtime·gateway·fake-pg는 `bootJar`를 고정 힙으로 `java -jar` 실행한다. `taskset`으로 k6와 앱의 CPU 코어를 나눈다. 측정은 3회 반복해 중앙값을 기록하고 하드웨어·버전을 함께 남긴다 |
| VT 점검 | JFR `jdk.VirtualThreadPinned` 이벤트와 Hikari `hikaricp.connections.pending`·`active`를 본다. 현재 Hikari 풀 크기는 기본값 10이다. JDK 25는 JEP 491로 `synchronized` pinning이 대부분 없어졌으므로 남은 pinning(네이티브 프레임 등)을 확인한다 |
| 결과 문서 | `docs/performance/` |

## 디렉터리

| 경로 | 내용 |
|---|---|
| `compose.observability.yaml`, `observability/` | 관측 스택 compose와 Prometheus·Tempo·Grafana 설정 |
| `loadtest/` | k6 TypeScript 시나리오(`scenarios/`)와 공용 모듈(`lib/`). k6 2.3은 TypeScript를 직접 실행한다 |
| `scripts/loadtest/` | k6 빌드, 시드, 앱 기동, 실행, 정합성 검증 스크립트 |
| `build/loadtest/` | k6 바이너리, 실행별 결과·로그·JFR (git 제외) |
| `docs/performance/` | 측정 결과 문서 |

## Step

| N | 이름 | 대상 | model / reasoning_effort |
|---|---|---|---|
| 0 | [loadtest-decisions-docs](./step0-loadtest-decisions-docs.md) | ADR 0024, architecture § 11 | haiku / medium |
| 1 | [metrics-tracing-core](./step1-metrics-tracing-core.md) | build-logic 공통 의존성, core·fake-pg 지표·추적 설정 | 기본 / medium |
| 2 | [metrics-tracing-reactive](./step2-metrics-tracing-reactive.md) | realtime·gateway 지표·추적, Reactor·Coroutines 컨텍스트 전파 | 기본 / medium |
| 3 | [observability-stack](./step3-observability-stack.md) | `compose.observability.yaml`, Prometheus·Tempo·Grafana 프로비저닝 | 기본 / medium |
| 4 | [loadtest-data-integrity](./step4-loadtest-data-integrity.md) | 회원 1만 명 시드, 회차 등록, 정합성 검증 스크립트 | 기본 / medium |
| 5 | [loadtest-runner](./step5-loadtest-runner.md) | k6(xk6-sse) 빌드, 앱 기동(힙·taskset·JFR), 실행 스크립트 | 기본 / medium |
| 6 | [k6-scenarios](./step6-k6-scenarios.md) | `loadtest/` JWT·SSE 헬퍼, 시나리오 A·B, thresholds | 기본 / high |
| 7 | [baseline-measurement](./step7-baseline-measurement.md) | 기준 측정, pinning·Hikari 분석, `docs/performance/m5-baseline.md` | 기본 / high |
| 8 | [docs-finalize](./step8-docs-finalize.md) | roadmap, architecture, runbook, AGENTS.md § 6 동기화 | haiku / medium |
