# 로컬 부하 테스트 실행 절차

이 문서는 M5-2의 `smoke` → 시나리오 A → 시나리오 B를 직접 실행하고 결과를 확인하는 절차다. 명령은 모두 저장소 루트에서 실행한다. 테스트는 로컬 부하 전용 DB·Redis를 사용하며, 각 실행에서 회원·공연장·공연·회차를 만들 수 있다. DB나 Redis 전체를 초기화하지 않는다.

## 먼저 확인: 이번 결과를 무엇과 비교할 것인가

2026-11-13 기준, 기존 Step 1 측정과 현재 작업 트리 사이에 Gateway 연결 풀 설정 변경이 있다. 추가 B 측정에는 Scouter도 활성화됐다. 관측·서버 설정이 다르면 결과를 같은 조건의 전후 비교로 합치지 않는다. 현재 설정으로 실행한 결과는 **현재 설정의 별도 측정**으로 기록한다. 기존 Step 1의 관측 보강만 적용한 기준을 정확히 다시 만들려면 해당 기준의 코드·설정과 관측 상태를 먼저 복원해야 한다. 불확실하면 이 절차의 smoke와 1회 확인까지만 하고 설정 차이를 먼저 정리한다.

기존 측정과 추가 실행의 한계는 [M5-2 개선 및 측정 인계](m5-improvements.md)를 확인한다. M5-2의 기준 측정은 A 10,000명·5,000석, B 1,000명·핫 좌석 100석이며 각 3회다. 워밍업 없이 회차마다 앱을 새로 띄운다.

## 1. 실행 환경 준비

1. Docker를 실행하고 `docker info`가 성공하는지 확인한다. Java 25, `curl`, `jq`, `taskset`, `python3`, `openssl`, `rg`와 Docker Compose가 필요하다. `./gradlew`를 실행할 수 있어야 한다.
2. 루트의 `.env`를 준비한다. 없으면 `.env.example`을 복사하고 로컬 PostgreSQL 접속 정보와 JWT 비밀 값을 설정한다. 이미 실행 중인 PostgreSQL의 계정·암호와 일치해야 한다. `.env` 내용은 공유하지 않는다.
3. 다른 Gradle 검사, E2E, 부하 테스트를 종료한다. 다른 측정과 동시에 실행하지 않는다. 로컬 앱·서비스가 TCP 포트 18000, 18080, 18081, 18090을 사용하지 않아야 한다.

```bash
docker info >/dev/null && echo 'Docker 준비됨'
git status --short
ss -ltnp | rg ':(18000|18080|18081|18090)\b' || true
```

포트 확인에서 줄이 출력되면 점유한 프로세스나 컨테이너를 확인하고 중지한다. 예를 들어 기본 fake-pg 컨테이너가 18090을 점유하면 `docker stop ticketing-fake-pg-1`로 잠시 중지하고, 테스트가 모두 끝나면 `docker start ticketing-fake-pg-1`로 복구한다. 다른 사람이 사용 중인 서비스라면 먼저 사용 여부를 확인한다. runner는 테스트용 fake-pg 앱을 직접 띄운다.

CPU 기본 배정은 k6 논리 코어 `0-3`, 앱 `4-15`다. 이 코어가 현재 실행 환경에서 사용 가능한지 `taskset -pc $$`로 확인한다. 배정을 바꾸면 결과의 실행 조건도 바뀌므로 비교 계열에 기록한다. k6 바이너리는 runner가 필요 시 Docker로 빌드한다.

## 2. 잘못된 공연장 캐시 비우기

현재 시드 스크립트는 `build/loadtest/venue.json`의 ID가 가리키는 공연장을 좌석 수 검증 없이 재사용한다. 최근 실행에서는 이 때문에 800석·핫 좌석 60석으로 잘못 측정됐다. **측정 시작 전에 캐시 파일만 옮겨** 새 5,000석 공연장을 만들게 한다. DB의 공연장 데이터는 삭제하지 않는다.

```bash
if [ -f build/loadtest/venue.json ]; then
  mv build/loadtest/venue.json "build/loadtest/venue.json.backup.$(date +%Y%m%d-%H%M%S)"
fi
```

이 조치는 다음 시드 실행에서 새 공연장을 만들게 한다. 이후에도 각 실행의 `seed.log`에서 실제 좌석 수를 반드시 확인한다. 기준과 다르면 그 실행은 기준 측정으로 사용하지 않는다. 추후 시드가 좌석 수를 자동 검증하도록 수정되기 전까지 이 확인을 생략하지 않는다.

## 3. Smoke 실행

```bash
./scripts/loadtest/run.sh --scenario smoke --observability
```

끝에 `thresholds 통과`가 나오면 다음으로 진행한다. 오류가 나면 출력된 로그 경로를 확인하고 해결한 뒤 다시 실행한다. smoke는 회차·좌석 규모를 검증하지 않는다.

## 4. 시나리오 A: 대기열 급증

우선 한 번 실행하고 좌석 규모를 확인한다. 각 명령이 끝날 때 출력하는 `디렉터리` 경로를 복사해 둔다.

```bash
./scripts/loadtest/run.sh --scenario queue-surge --observability
```

출력된 경로를 아래 `RUN_DIR`에 넣고 확인한다.

```bash
RUN_DIR='여기에 출력된 실행 디렉터리 경로'
rg '회차 등록 완료' "$RUN_DIR/seed.log"
cat "$RUN_DIR/measurement.txt"
```

`좌석 5000석, hot 100석`이어야 한다. 맞지 않으면 이후 회차를 실행하지 말고 해당 결과와 로그를 보존한다. 맞으면 같은 명령을 **두 번 더** 실행하고 각 회차에서 위 확인을 반복한다. 세 실행 디렉터리가 A의 3회 결과다.

A는 모든 VU가 끝나면 즉시 종료하지만 최대 15분과 종료 유예 30초를 기다릴 수 있다. 대부분이 완료돼도 일부 VU가 남으면 상한까지 걸릴 수 있다. 앱 빌드·기동과 종료 후 정합성 검사 시간은 별도다.

## 5. 시나리오 B: 인기 좌석 경합

```bash
./scripts/loadtest/run.sh --scenario hot-seats --observability
```

출력된 `디렉터리`를 `RUN_DIR`에 넣고 A와 같은 명령으로 `seed.log`와 `measurement.txt`를 확인한다. `좌석 5000석, hot 100석`이면 같은 명령을 두 번 더 실행한다. 세 실행 디렉터리가 B의 3회 결과다.

### AOT 캐시 기동으로 측정하기

```bash
./scripts/loadtest/run.sh --scenario hot-seats --runs 3 --observability --no-scouter --aot-cache [--aot-train-rounds 15]
```

jar를 풀고 학습 기동(hot-seats 부하 N회)으로 캐시를 만든 뒤, 측정 기동마다 `-XX:AOTMode=on`으로 새 프로세스를 띄운다. 캐시를 못 쓰면 기동이 실패한다. 학습은 실행당 1회이며 `aot/train/`에 로그가 남는다. `env.txt`에 사용 여부·학습 횟수·캐시 크기가 기록되고 `<앱>-aot.log`로 캐시 사용을 확인한다. `--warmup`과 함께 쓰지 않는다. 결정 배경은 [ADR 0026](../adr/0026-aot-cache-startup.md)이다.

B의 60초 동시 시작 대기는 워밍업이 아니다. 모든 요청이 일찍 실패하면 60초 이전에 끝날 수도 있다. 대기 중인 VU가 남으면 최대 10분과 종료 유예 30초까지 걸릴 수 있다.

## 6. 매 회차 결과 확인

각 실행의 요약에 나온 디렉터리를 `RUN_DIR`에 지정해 아래를 실행한다.

```bash
RUN_DIR='여기에 출력된 실행 디렉터리 경로'
rg '회차 등록 완료' "$RUN_DIR/seed.log"
cat "$RUN_DIR/measurement.txt"
cat "$RUN_DIR/integrity.log"
rg 'interrupted iterations|thresholds on metrics' "$RUN_DIR/k6.log" | tail -n 3
jq '{flow_registered: .metrics.flow_registered.count, flow_admitted: .metrics.flow_admitted.count, flow_ready_on_time: .metrics.flow_ready_on_time.count, flow_booking_attempt: .metrics.flow_booking_attempt.count, flow_booking_created: .metrics.flow_booking_created.count, unexpected_errors: .metrics.unexpected_errors.value, sse_connect_success: .metrics.sse_connect_success.value, booking_p95_ms: .metrics.duration_booking_create["p(95)"]}' "$RUN_DIR/k6-summary.json"
```

- `measurement.txt`의 시각은 Unix 초이며 `k6_exit_code`는 runner가 아닌 k6의 종료 코드다. `99`는 threshold 미달이다. runner 자체는 99여도 성공 종료할 수 있으므로 둘을 혼동하지 않는다.
- `integrity.log`와 `integrity.json`에서 중복 판매 0건과 DB·Redis 정합성을 확인한다. 위반 또는 검증 실패는 그대로 보존하고 원인 확인 전에는 합격으로 표시하지 않는다.
- B는 `flow_ready_on_time`이 1,000에 못 미치면 1,000명 동시 경합의 합격 근거가 아니다. 예매 시도가 없으면 p95가 0으로 표시되더라도 지연 표본 없음이다. `null`은 관측값 없음이지 0명이 아니다.
- `interrupted iterations`가 0이 아니면 중단 건수와 원인을 기록한다. `unexpected_errors`와 `sse_connect_success`는 비율이다. `http_req_failed`와 예상 밖 오류율은 별도 지표다.
- 대기열 입장 이벤트를 받지 못한 원인은 다음 명령으로 확인한다. 없는 지표는 해당 원인이 기록되지 않았다는 뜻이며, 기존 실행에는 이 분류가 없다.

```bash
jq '.metrics | {queue_stream_removed, queue_stream_error, queue_stream_closed_without_result, queue_stream_ticket_failed}' "$RUN_DIR/k6-summary.json"
```

`queue_stream_removed`는 서버의 removed 이벤트, `queue_stream_error`는 SSE 오류 콜백, `queue_stream_closed_without_result`는 결과 이벤트 없이 종료된 경우, `queue_stream_ticket_failed`는 SSE 티켓을 받지 못한 경우다. 마지막 둘을 시간 초과라고 단정할 수 없다. 중단된 iteration은 종료 사유가 기록되지 않을 수 있다. 이 값은 기존 오류율 분류를 바꾸지 않는 진단 카운터다.

- 잠정 기준은 예매 생성 p95 300ms 미만, 예상 밖 오류율 1% 미만, SSE 연결 성공률 99.9% 초과다. 기준 미달을 숨기지 않는다.

`env.txt`에는 커밋과 dirty 상태, CPU 배정, 실행 설정이 있다. `resources.csv`는 자원 표본, `prometheus.json`은 관측 지표다. 로그와 산출물은 `build/loadtest/runs/`에 보존하며 Git에 추가하지 않는다.

## 7. 결과 공유와 문제 해결

세 회차가 끝나면 A와 B 각각의 요약 전체, 실행 디렉터리, 좌석 수, 중단 여부, 설정 변경을 함께 공유한다. 한 회차가 실패하거나 중단되면 삭제하지 말고 경로와 실패 메시지를 남긴 뒤 재실행 사유를 기록한다. 결과는 [M5-2 개선 및 측정 인계](m5-improvements.md)에 반영한다.

| 증상 | 확인과 조치 |
|---|---|
| 포트 사용 중 | `ss`와 `docker ps`로 점유자를 확인하고 관련 로컬 서비스를 정리한 뒤 재실행한다. |
| 좌석이 800석 등으로 표시됨 | 결과를 보존하고 2절의 공연장 캐시 조치를 다시 확인한다. 새 시드 결과가 5,000석·100석인지 확인한다. |
| DB 암호 오류 | `.env`와 실행 중인 로컬 PostgreSQL의 접속 정보가 일치하는지 확인한다. 기존 DB를 삭제해 해결하지 않는다. |
| A가 15분 넘게 대기 | 15분 상한과 30초 종료 유예까지 기다린 뒤 `k6.log`의 중단 건수를 확인한다. |
| B의 p95가 0 | `flow_booking_attempt`와 `duration_booking_create`를 확인한다. 예매 표본이 없으면 측정값 없음으로 기록한다. |
| threshold 미달 99 | 실패 수치와 원본 로그를 보존한다. 정상 응답으로 분류를 바꾸거나 재시도로 감추지 않는다. |

## 8. OOM 힙 덤프 분석 (MAT)

기동 스크립트는 `-XX:+HeapDumpOnOutOfMemoryError`를 붙인다. OOM이 나면 부하테스트는 `build/loadtest/runs/<run>/<n>/<앱>.hprof`, 개발 기동은 `build/dev/logs/<앱>.hprof`에 덤프가 남는다. 크기는 힙과 비슷하고(realtime 1GB 힙에서 1.7GB) `build/`는 git에 올라가지 않는다.

### 헤드리스로 리포트 만들기

```bash
MAT=/home/hong/dev/tool/java/mat
$MAT/ParseHeapDump.sh <앱>.hprof org.eclipse.mat.api:suspects org.eclipse.mat.api:top_components
```

- 같은 디렉터리에 `<앱>_Leak_Suspects.zip`, `<앱>_Top_Components.zip`과 `*.index` 파일이 생긴다. 1.7GB 덤프에 약 7분 걸리므로 백그라운드로 실행한다.
- MAT 자체 힙이 모자라면 `$MAT/MemoryAnalyzer.ini`의 `-Xmx`를 덤프 크기 이상으로 올린다.
- 같은 덤프를 다시 열면 `*.index`를 재사용해 빠르다. 분석이 끝나면 hprof와 index를 지운다.

### 읽는 순서

1. `Leak_Suspects.zip`을 풀어 `index.html`을 연다. 첫 문장이 "N개 인스턴스가 힙의 X%를 차지한다"이다. 이번 건은 `TraceContext` 9,850개가 51.7%였다.
2. `pages/<번호>.html`(Problem Suspect)의 **Common Path To the Accumulation Point**에서 누가 붙들고 있는지 본다. 이번 건은 `TraceContextManager.entryByTxid`(`SimpleLru`)였다.
3. **All Objects by Class Retained by Suspect Objects**에서 무엇이 용량을 채우는지 본다. 개수를 suspect 인스턴스 수로 나누면 인스턴스당 크기가 나온다.
4. `pages/Class_Histogram*.html`로 앱 전체 객체 수 상위를 본다. 한 가지 클래스가 수십만 개 이상이면 단서다.
5. 인스턴스 수가 요청·연결 수와 비슷한지 Grafana의 진행 중 요청 패널과 대조한다. 비슷하면 요청당 누적이다.
6. OOM 스택의 맨 위(`DefaultContextSnapshot.setThreadLocal` 등)는 힙이 이미 찬 뒤 할당에 실패한 지점이다. 원인으로 읽지 않는다.

GUI로 볼 때는 `$MAT/MemoryAnalyzer`에서 hprof를 열고 Dominator Tree(보존 크기 순)와 Histogram을 쓴다. 객체를 우클릭해 *Path to GC Roots → exclude weak/soft references*를 보면 해제되지 않는 이유가 나온다.

### 사례: Scouter 에이전트가 SSE 연결마다 프로파일을 쌓음

- 증상: Scouter를 켰을 때만 realtime(`/api/v1/streams/queues/{scheduleId}`)에서 `OutOfMemoryError: Java heap space`.
- 덤프: `TraceContext` 9,850개(연결당 약 55KB)가 `ThreadCallPossibleStep` 222만 개, `MethodStep` 95만 개, `ParameterizedMessageStep` 55만 개를 들고 있었다.
- 조치: realtime은 별도 설정 파일 `deploy/scouter/ticketing-realtime.conf`를 쓰고, 여기서 `hook_async_*` 세 항목을 끄고 `*Service.*`를 메서드 추적에서 뺐다(공통 항목을 고치면 `ticketing.conf`와 같이 고친다). 1차 조치(async 훅 off, `*Service.*` 추적 제외) 후 재측정한 덤프에서도 `TraceContext` 약 9,900개가 힙의 51.5%였다. `ThreadCallPossibleStep`은 사라지고 `MethodStep`은 연결당 3개로 줄었지만 `ParameterizedMessageStep` 96만 개(보존 391MB)가 남았다. 리액터 체크포인트(`profile_reactor_checkpoint_enabled`)를 꺼도 줄지 않았다. MAT OQL로 스텝 내용을 뽑아 보니 `EXISTS key<booking:seats:{30}>` 같은 Redis 명령이었다. 2차 조치로 realtime 설정에서 `_hook_redis_enabled=false`를 두었으며 효과는 재측정 전이다.

MAT로 스텝 내용 보기(인덱스가 있으면 빠르다):

```bash
$MAT/ParseHeapDump.sh <앱>.hprof -command='oql "SELECT s.hash, toString(s.paramString) FROM scouter.lang.step.ParameterizedMessageStep s"' org.eclipse.mat.api:query
```

`-command`는 report ID(`org.eclipse.mat.api:query`)보다 앞에 둬야 한다. `-format`, `-limit`을 붙이면 `Invalid command line` 오류가 난다.

실제로 붙은 에이전트는 덤프의 System Properties(`scouter.dir`, `scouter.config`)로 확인한다. 이번에는 `/home/hong/dev/tool/scouter-2.21/agent.java21plus`였다.
