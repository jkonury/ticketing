# Step 3: observability-stack

- model: 기본 / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `compose.yaml` (기존 인프라. 이미지 태그 고정, `127.0.0.1` 바인딩, `.env` 사용 방식을 따른다)
- `.env.example` (PostgreSQL 접속 변수 이름)
- `core/src/main/resources/application.yml`의 `management.*` 절 (step 1 결과. 지표 이름·태그 확인용)

## 배경

부하 테스트 중 앱 지표, 인프라 지표, k6 지표, trace를 Grafana 한 곳에서 본다(ADR 0024). 앱은 호스트에서 `java -jar`로 뜨고(포트 gateway 8000, core 8080, realtime 8081, fake-pg 8090), 관측 컨테이너가 호스트의 앱을 수집한다. 앱의 span 내보내기는 `TRACING_EXPORT_ENABLED=true`, `OTLP_TRACES_ENDPOINT=http://localhost:4318/v1/traces`로 켠다(step 1·2).

## 작업

이미지는 공식 저장소의 최신 안정 태그를 확인해 고정한다(`latest` 금지). 설정 키는 각 제품의 공식 문서로 확인한다.

### 1. `compose.observability.yaml` (새 파일)

`docker compose -f compose.yaml -f compose.observability.yaml up -d`로 기존 인프라와 함께 뜬다. 모든 포트는 `127.0.0.1`에 바인딩한다.

| 서비스 | 포트 | 요점 |
|---|---|---|
| `prometheus` | 9090 | scrape 간격 5s. remote-write 수신(k6 지표), exemplar 저장 기능을 켠다. `extra_hosts: host.docker.internal:host-gateway` |
| `tempo` | 3200, 4318 | OTLP HTTP 수신, 로컬 파일 저장, 보존 기간 짧게(예: 24h) |
| `grafana` | 3001 | 로컬 전용 익명 Admin 로그인. datasource·대시보드 프로비저닝 |
| `redis-exporter` | 내부 | 기존 `redis` 서비스 수집 |
| `postgres-exporter` | 내부 | 기존 `postgres` 서비스 수집. 접속 정보는 `.env` 변수 사용 |

### 2. `observability/` (새 디렉터리)

- `prometheus/prometheus.yml`: job `apps`(대상 `host.docker.internal:8000`, `:8080`, `:8081`, `:8090`, 경로 `/actuator/prometheus`), `redis`, `postgres`, `prometheus` 자신.
- `tempo/tempo.yaml`
- `grafana/provisioning/datasources/datasources.yaml`: Prometheus(기본, exemplar의 traceId 라벨 → Tempo 연결), Tempo(trace → Prometheus 지표 연결).
- `grafana/provisioning/dashboards/dashboards.yaml`와 `grafana/dashboards/ticketing-overview.json`. 패널(`application` 변수로 앱 선택):
  - HTTP: 앱·URI별 RPS, p95·p99(`http_server_requests_seconds_bucket`), 5xx·4xx 비율
  - DB: Hikari `active`·`idle`·`pending`·acquire 시간, PostgreSQL 연결 수·TPS
  - JVM: 힙, GC 일시 정지, 플랫폼 스레드 수, Virtual Thread 지표(step 1에서 등록한 이름), CPU
  - Redis: 명령 처리량, 연결 수, 메모리
  - k6: VU 수, 요청 수, `http_req_duration` p95(k6 Prometheus remote-write 지표 이름은 k6 공식 문서로 확인)

### 3. `.gitignore`

Grafana·Prometheus·Tempo 데이터를 named volume에 두면 추가할 것이 없다. 바인드 마운트로 데이터를 저장소 안에 두지 않는다.

## Acceptance Criteria

```bash
docker compose -f compose.yaml -f compose.observability.yaml config -q
bash -o pipefail -c 'jq empty observability/grafana/dashboards/ticketing-overview.json'
bash -o pipefail -c 'docker run --rm --entrypoint promtool -v "$PWD/observability/prometheus:/cfg:ro" prom/prometheus:$(rg -o "prom/prometheus:([^\"\s]+)" -r "\$1" compose.observability.yaml) check config /cfg/prometheus.yml'
```

## 검증 절차

1. 위 AC를 실행한다.
2. 실제로 띄워 확인한다: `docker compose -f compose.yaml -f compose.observability.yaml up -d --wait` 뒤 `curl -sf localhost:9090/-/ready`, `curl -sf localhost:3001/api/health`, `curl -sf localhost:3200/ready`. Prometheus `/api/v1/targets`에서 `redis`·`postgres` 대상이 `up`인지 확인한다(`apps`는 앱을 띄우지 않았으므로 `down`이 정상).
3. 확인 후 `docker compose -f compose.yaml -f compose.observability.yaml down`으로 관측 컨테이너를 정리한다. 볼륨은 지우지 않는다(`-v` 금지).

## 금지사항

- `compose.yaml`에 관측 서비스를 넣지 마라. 이유: 평소 개발과 E2E(`scripts/e2e/run.sh`)는 관측 스택 없이 떠야 한다.
- Grafana를 3000 포트에 두지 마라. 이유: web(Next) 개발 서버와 E2E `--web`이 3000을 쓴다.
- `0.0.0.0`에 포트를 바인딩하지 마라. 이유: 익명 Admin Grafana가 외부에 열린다.
- 대시보드 JSON을 Grafana UI에서 export한 거대한 형태로 넣지 마라. 패널은 위 목록으로 제한한다. 이유: 리뷰가 어렵고 이후 수정 diff가 커진다.
