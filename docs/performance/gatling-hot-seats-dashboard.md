# Gatling hot-seats 전용 대시보드와 스크립트 지표

이번 p95 300ms 측정용 대시보드는 [Ticketing · Gatling hot-seats 300ms](http://localhost:13001/d/ticketing-hot-seats-gatling)다. [기존 Overview](http://localhost:13001/d/ticketing-overview)와 별도 UID·JSON 파일로 관리하며 기존 파일은 수정하지 않았다.

설정은 [대시보드 JSON](../../observability/grafana/dashboards/ticketing-hot-seats-gatling.json), [관측 Compose](../../compose.observability.yaml), [Prometheus 설정](../../observability/prometheus/prometheus.yml)에 있다. 구현·측정의 최신 상태와 남은 검증은 [인계 문서](../plans/hot-seats-gatling-handoff.md)를 따른다.

## 사용과 수집 방식

```bash
mkdir -p build/loadtest/runs
docker compose -f compose.yaml -f compose.observability.yaml up -d --wait prometheus grafana loadtest-exporter
# 이미 실행 중이던 Prometheus에 scrape 설정을 다시 읽히는 경우
docker compose -f compose.yaml -f compose.observability.yaml kill -s SIGHUP prometheus
```

상단에서 시나리오, Scouter on/off, 실행 ID, 단계를 고른다. 단계는 `starting`, `warmup`, `measurement`, `completed`, `failed`다. 실행 도중 외부 강제 종료는 마지막 단계가 남을 수 있으므로 `updated_seconds`와 원본 산출물을 확인한다. 여러 실행 ID를 고르면 실행별·Scouter별 결과를 함께 볼 수 있다. 본 측정 비교는 완료된 본 측정 결과만 선택하며 예열 표본을 섞지 않는다.

[발행 스크립트](../../scripts/loadtest/dashboard-metrics.py)는 러너 산출물에서 허용된 수치·라벨만 읽어 독립 실행 디렉터리의 `dashboard.prom`을 원자적으로 교체한다. exporter는 최근에 갱신된 독립 실행 최대 200개의 지표 파일만 읽어 Docker 내부 19100 포트에서 제공한다. 원시 로그·JWT·전체 환경 변수는 노출하지 않는다. Prometheus는 5초마다 이를 수집하며 실행이 끝나도 보존된 지표 파일에서 결과를 읽을 수 있다. Prometheus 시계열 보존 기간은 기존 설정의 2일이다.

스크립트 결과는 누적 Counter가 아니라 **실행 결과 Gauge**다. `rate`·`increase`를 적용하지 않는다. 파일이 없거나 표본이 없으면 해당 결과를 발행하지 않고 대시보드에 미수집으로 남긴다. 예열과 본 측정의 아티팩트 디렉터리를 분리한다. 이 대시보드를 추가하기 전의 결과는 자동 변환하지 않는다.

## 스크립트가 관측하는 주요 지표

접두사는 모두 `ticketing_loadtest_`다. 공통 라벨은 `run_id`, `scenario`, `scouter`, `phase`다.

| 지표 | 원본 | 의미·판정 |
|---|---|---|
| `booking_latency_seconds`, `booking_requests` | Gatling 요약·원시 로그 | 전체/2xx/정상 409/429/기타의 p50·p95·p99와 건수. 전체·성공 p95 각각 300ms 미만 |
| `booking_p95_limit_seconds` | 러너 옵션 | 실제 판정 한도. 미지정 시 VU별 기존 한도 |
| `readiness` | 준비 현황 | 등록·입장·정시 준비·지각·예매 시도. hot-seats는 전원 준비·지각 0 |
| `flow_count` | Gatling 사용자 정의 카운터 | 생성·결제·확정·충돌·확정 좌석 등 흐름별 건수 |
| `http_requests`, `http_failed_requests`, `sse_success_ratio` | Gatling 원시 요청 | HTTP 실패와 SSE 연결 성공률. 정상 409를 생성 성공 표본으로 사용하지 않음 |
| `passed`, `check_passed`, `verdict_available` | 판정 파일 | 전체·항목별 합격 여부와 판정 존재 여부. 판정 누락을 합격으로 표시하지 않음 |
| `integrity_ok`, `integrity`, `bookings` | 정합성 파일 | 중복 판매·Redis 불일치·판매 좌석 수·상태별 예매 수 |
| `server_delta_valid`, `controlled_input_*` | 서버 차분 | 차분 유효성과 Gatling 시도 ↔ Gateway/core 요청 수 일치 |
| `server_latency_seconds`, `server_requests` | 서버 누적 히스토그램 차분 | Gateway 단계·route, core HTTP·예매 단계, Hikari·연결 풀의 p50·p95·p99와 건수. 히스토그램 보간값 |
| `gc_pause_seconds`, `hikari_timeouts` | 서버 차분 | 본 측정의 앱별 GC pause 합계·연결 획득 timeout |
| `generator_rss_max_bytes`, `generator_cpu_max_cores` | 1초 자원 CSV | 발생기 RSS·CPU 표본 최대. CPU는 코어 환산 |
| `host_cpu_max_ratio`, `host_memory_max_ratio` | 1초 자원 CSV | 호스트 CPU·메모리 사용률 표본 최대. 순간 최대 보장은 아님 |
| `info`, `updated_seconds`, `warmup_round`, `warmup_rounds_planned` | 실행 메타데이터 | 실행 조건·갱신 시각·예열 진행 회차·예정 횟수 |

서버 차분 분위수는 Gatling의 정확한 HTTP 표본 분위수와 정의가 다르다. 서로 다른 요청 집합의 p95 차이를 구간 비용으로 해석하지 않는다. 최종 판정과 보고서는 로컬 원시 결과·판정 파일을 근거로 한다.

## 실시간 서버 패널

Gateway/core HTTP 지연과 요청률, Gateway·core 단계별 지연, 좌석 사본 캐시, Hikari 사용·대기·최대 연결, Gateway 연결 풀·획득 대기, 앱 CPU·힙·GC, Virtual Thread pinning·제출 실패, 대기열·Redis·PostgreSQL 지표를 표시한다.

실시간 앱 지표에는 실행 ID가 없으므로 **상단 실행 ID 선택이 적용되지 않는다.** 해당 실행의 시간 범위를 직접 설정한다. 30초 rate와 5초 scrape로 보는 추세이며, 예열 누적값을 본 측정의 최종 분포로 쓰지 않는다. 본 측정 확정 수치는 위쪽의 스크립트 차분 패널을 사용한다.
