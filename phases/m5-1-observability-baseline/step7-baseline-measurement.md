# Step 7: baseline-measurement

- model: 기본 / reasoning_effort: high

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `phases/m5-1-observability-baseline/README.md` (합의된 결정, 잠정 합격 기준)
- `scripts/loadtest/run.sh` (step 5·6 결과. 옵션과 실행 디렉터리 구조)
- `loadtest/lib/metrics.ts` (사용자 지표 이름)
- `observability/grafana/dashboards/ticketing-overview.json` (패널별 PromQL. 같은 쿼리를 재사용한다)
- `docs/glossary.md`에서 "증상 / 원인" 행 (추정과 확인을 구분해 쓰는 규칙)

## 배경

기준 수치를 측정하고 병목 후보를 근거와 함께 정리한다. 이 문서가 M5-2(개선) 계획의 입력이 된다. **코드와 설정은 바꾸지 않는다.**

## 작업

### 1. 지표 추출 스크립트 `scripts/loadtest/prom-snapshot.sh <from-epoch> <to-epoch> <outdir>`

Prometheus HTTP API(`/api/v1/query_range`, `/api/v1/query`)로 실행 구간의 다음 값을 `<outdir>/prometheus.json`에 저장한다. `run.sh --observability`가 실행마다 이 스크립트를 호출하도록 연결한다.

- 앱·URI별 서버 측 p95·p99와 RPS 최대값
- Hikari `pending` 최대값, `active` 최대값, acquire 시간 p95
- 앱별 프로세스 CPU 최대·평균, 힙 사용 최대, GC 일시 정지 합계
- Virtual Thread 지표(pinning 수 등, step 1에서 등록한 이름)
- Redis 명령 처리량 최대, PostgreSQL 활성 연결 최대

### 2. 측정 실행

같은 머신에서 다른 무거운 작업 없이 순서대로 실행한다. 실행 하나가 길므로 Bash 도구는 백그라운드 실행을 쓰고 완료 알림을 기다린다.

| 순서 | 명령 | 목적 |
|---|---|---|
| 1 | `run.sh --scenario queue-surge --runs 3 --observability` | 시나리오 A 기준값 |
| 2 | `run.sh --scenario hot-seats --runs 3 --observability` | 시나리오 B 기준값 |
| 3 | `run.sh --scenario queue-surge --jfr` | pinning 분석. JFR 부담 때문에 1·2와 분리한다 |
| 4 | `run.sh --scenario hot-seats --failure-inject --observability` | 실패 10%·웹훅 중복 30% 상황의 정합성. 좌석 경합과 보상(`SEAT_ALREADY_SOLD`, 환불)이 함께 일어나는 B로 한다 |

- 어떤 실행이든 `verify-integrity.sh`가 실패하면 **즉시 중단하고** 실행 디렉터리와 위반 내용을 보고한다. 중복 판매는 M5의 절대 기준이다.
- 스크립트 오류로 실행이 깨지면 원인을 고치고 그 실행만 다시 한다. 고친 내용은 결과 보고에 적는다.
- 1만 VU 실행이 k6 메모리 부족 등 부하 발생기 문제로 실패하면 규모를 줄이지 말고 증상과 측정값을 보고한다.

### 3. 분석

- 실행별 지표 표를 만들고 3회 중앙값을 낸다: k6 `http_req_duration`(이름별 p95·p99), `queue_wait` p50·p95, `confirm_latency` p95, 처리량(RPS), `unexpected_errors`, `sse_connect_success`, `booking_conflict`, `seats_confirmed`, thresholds 통과 여부.
- 서버 측 지표(`prometheus.json`)와 k6 지표의 차이로 Gateway·네트워크 구간 지연을 추정한다.
- JFR: `jfr summary`와 `jfr print --events jdk.VirtualThreadPinned`로 앱별 pinning 수, 최장 시간, 상위 스택 프레임(호출 위치)을 정리한다. 출력은 파일로 저장하고 요약만 본다.
- Hikari: `pending` 최대값이 0보다 크면 그 구간의 `active`(풀 크기 10에 닿았는지)와 요청 지연을 함께 본다.
- 병목 후보를 영향 순서로 3~5개 적는다. 각각 증상(지표 값) / 원인(확인됨·추정 구분) / 근거(실행 디렉터리, 쿼리) / M5-2 개선 아이디어와 기대 효과.

### 4. `docs/performance/m5-baseline.md` (새 파일)

1. 측정 환경: CPU 모델·코어 수, 메모리, OS, JDK, 커밋 해시, 코어 분리(`--k6-cpus`·`--app-cpus`), 힙, 샘플링 비율. `env.txt`에서 옮긴다.
2. 시나리오와 실행 방법: 명령, VU 수, 좌석 수, 입장 설정, fake-pg 설정.
3. 결과 표(실행 1~3, 중앙값)와 thresholds 통과 여부.
4. 정합성: 실행별 `verify-integrity` 결과(중복 판매 0건 여부, 확정 좌석 수, 상태별 예매 수).
5. Virtual Thread 점검: pinning 결과, Hikari 대기.
6. 병목 후보와 M5-2 제안.
7. 한계: 단일 머신, k6와 앱의 동일 호스트 실행, 샘플링 0.1.

Grafana 화면 캡처는 넣지 않는다. 수치는 표로 남긴다.

## Acceptance Criteria

```bash
test -f docs/performance/m5-baseline.md
rg -q '중앙값' docs/performance/m5-baseline.md
rg -q 'VirtualThreadPinned' docs/performance/m5-baseline.md
rg -q 'pending' docs/performance/m5-baseline.md
bash -n scripts/loadtest/prom-snapshot.sh
./scripts/agent/docs/check-docs.sh docs/performance
```

## 검증 절차

1. 위 AC를 실행한다.
2. 문서의 모든 수치가 실행 디렉터리 산출물(k6 요약 JSON, `prometheus.json`, `integrity-*.json`)과 일치하는지 표마다 한 번 대조한다.

## 금지사항

- 서버 코드·설정(Hikari 풀 크기, rate limit, 입장 속도, 힙)을 바꾸지 마라. 이유: 기준 측정이다. 개선은 M5-2에서 사용자와 합의한 뒤 한다.
- 결과가 나쁘다는 이유로 실행을 골라 버리거나 다시 돌려 좋은 값을 고르지 마라. 이유: 3회 모두 기록하고 중앙값을 쓴다. 다시 돌린 실행은 이유와 함께 적는다.
- 추정을 원인으로 단정하지 마라. 이유: M5-2가 이 문서를 근거로 개선 대상을 고른다. 확인된 원인과 추정을 구분한다.
- `build/loadtest/` 산출물을 git에 넣거나 `docs/`로 복사하지 마라. 이유: 크기가 크고 재현 가능하다. 문서에는 요약 수치와 실행 디렉터리 이름만 적는다.
- `docs/roadmap.md`를 바꾸지 마라. 이유: step 8에서 갱신한다.
