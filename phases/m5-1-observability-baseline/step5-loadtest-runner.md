# Step 5: loadtest-runner

- model: 기본 / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `scripts/e2e/run.sh` (포트 검사, jar 빌드, 프로세스 기동·대기·정리 방식. 같은 관례를 따르되 코드를 공유하도록 리팩터링하지는 않는다)
- `scripts/loadtest/seed.sh`, `scripts/loadtest/verify-integrity.sh` (step 4 결과. 호출 인자와 산출물 경로)
- `compose.observability.yaml` (step 3 결과. Prometheus remote-write 주소)
- `fake-pg/src/main/resources/application.yml` (`fake-pg.behavior.*`, `fake-pg.webhook.*` 속성)
- `realtime/src/main/kotlin/io/github/ticketing/realtime/queue/QueueProperties.kt` (`ticketing.realtime.queue.admission-batch-size`)

## 배경

측정 실행을 한 명령으로 재현할 수 있게 한다(ADR 0024). 앱은 호스트에서 `java -jar`로 띄우고, `taskset`으로 k6와 앱의 CPU를 나눈다. 시나리오 스크립트는 step 6에서 만든다. 이 step은 실행기와 동작 확인용 `smoke` 시나리오만 만든다.

## 작업

xk6 빌드 방법, xk6-sse 모듈 경로·버전, k6 Prometheus remote-write 출력 이름과 환경 변수, JFR 옵션 문법은 각각 공식 문서(README)로 확인한다.

### 1. `scripts/loadtest/build-k6.sh`

- Docker `grafana/xk6` 이미지(태그 고정)로 k6 v2.3.0 + xk6-sse(태그 고정, k6 v2 호환 확인)를 빌드해 `build/loadtest/k6`에 둔다.
- 바이너리가 있고 `build/loadtest/k6 version` 출력에 고정한 버전이 모두 들어 있으면 다시 빌드하지 않는다(`--force`로 강제).

### 2. `scripts/loadtest/run.sh`

```text
run.sh --scenario <smoke|queue-surge|hot-seats> [--runs N] [--failure-inject] [--jfr] [--observability] [--keep]
       [--k6-cpus 0-3] [--app-cpus 4-15]
```

순서:

1. 사전 검사: `.env`, Docker, 포트 8000·8080·8081·8090이 비어 있음. `smoke`가 아니면 `build-k6.sh`를 실행한다.
2. `./gradlew -q :core:bootJar :gateway:bootJar :realtime:bootJar :fake-pg:bootJar` (로그는 실행 디렉터리).
3. `docker compose up -d --wait postgres redis`. `--observability`면 `-f compose.observability.yaml`을 더해 관측 스택도 띄운다. compose의 `fake-pg` 컨테이너는 띄우지 않는다(호스트 jar로 대체).
4. 실행마다(`--runs`, 기본 1):
   - 앱 4개를 `taskset -c <app-cpus>`로 띄운다. 힙 고정: core `-Xms2g -Xmx2g`, realtime·gateway `-Xms1g -Xmx1g`, fake-pg `-Xms512m -Xmx512m`. `--observability`면 `TRACING_EXPORT_ENABLED=true`, `TRACING_SAMPLING_PROBABILITY=0.1`. `--jfr`면 각 앱에 JFR 기록을 켜고 `jdk.VirtualThreadPinned` 이벤트를 임계값 없이(모두) 기록하게 한다.
   - fake-pg 웹훅 URL은 `http://localhost:8080/api/v1/payments/webhooks/fake-pg`, 비밀 값은 `.env`. `--failure-inject`면 fake-pg 실패율 0.1, 웹훅 중복률 0.3. 그 밖에는 0.
   - `hot-seats`면 realtime 입장 배치 크기를 1000으로 바꾼다(환경 변수 relaxed binding).
   - actuator health가 모두 UP일 때까지 기다린다(최대 90초, 실패 시 해당 로그 tail 30줄).
   - `seed.sh members`(멱등), `smoke`가 아니면 `seed.sh schedule --scenario <scenario>`.
   - k6를 `taskset -c <k6-cpus>`로 실행한다: `loadtest/scenarios/<scenario>.ts`, 환경 변수 `BASE_URL=http://localhost:8000`, `TICKETING_JWT_SECRET`, `MEMBERS_FILE`, `SCHEDULE_FILE`. `--observability`면 Prometheus remote-write 출력을 켠다. `--summary-export`(또는 k6 2.x의 대응 옵션)로 요약 JSON을 남긴다.
   - `smoke`가 아니면 결제 웹훅이 끝나도록 10초 기다린 뒤 `verify-integrity.sh <scheduleId>`.
   - 앱을 종료한다(JFR이 파일로 남도록 SIGTERM 후 대기).
5. 실행 디렉터리: `build/loadtest/runs/<yyyyMMdd-HHmmss>-<scenario>[-fi]/<run번호>/`에 앱 로그, k6 요약 JSON, k6 stdout, JFR, 정합성 결과, `env.txt`(커밋 해시, JDK 버전, CPU 모델·코어 수, 메모리, 옵션)를 둔다.
6. 종료 시 출력은 요약만: 실행별 k6 thresholds 통과 여부, 주요 지표(요청 수, `http_req_failed`, 예매 생성 p95), 정합성 결과, 실행 디렉터리 경로. `--keep`이 아니면 compose 인프라는 그대로 두고 앱만 정리한다.
7. 중간 실패(trap)에도 띄운 앱 프로세스를 모두 정리한다.

### 3. `loadtest/scenarios/smoke.ts`

- `import sse from 'k6/x/sse'`가 로드되는지(커스텀 빌드 확인), Gateway `GET /api/v1/performances`가 200인지 VU 1명 × 1회로 확인한다. 실패 시 threshold로 exit 1.
- `loadtest/tsconfig.json`이나 타입 정의가 필요하면 최소로 둔다. 빌드 단계(번들러)는 두지 않는다. k6 2.3은 `.ts`를 직접 실행한다.

## Acceptance Criteria

```bash
bash -n scripts/loadtest/run.sh scripts/loadtest/build-k6.sh
test -x scripts/loadtest/run.sh && test -x scripts/loadtest/build-k6.sh
test -f loadtest/scenarios/smoke.ts
./scripts/loadtest/build-k6.sh && build/loadtest/k6 version
./scripts/loadtest/run.sh --scenario smoke
```

## 검증 절차

1. 위 AC를 실행한다. `run.sh --scenario smoke`는 실제 앱을 띄우므로 포트가 비어 있어야 한다.
2. `run.sh --scenario smoke --jfr --observability`를 한 번 실행해 JFR 파일이 생기는지, Prometheus `/api/v1/targets`의 `apps` 대상이 실행 중 `up`이었는지(로그나 실행 중 조회로) 확인한다. 끝나면 `docker compose -f compose.yaml -f compose.observability.yaml down`.

## 금지사항

- `scripts/e2e/run.sh`를 고치거나 공용 함수로 뽑아내지 마라. 이유: E2E 동작을 바꾸면 이 step 범위를 넘는다.
- 앱을 `bootRun`으로 띄우지 마라. 이유: Gradle 데몬이 CPU·메모리를 써서 측정이 흔들린다.
- `compose.yaml`의 fake-pg 설정을 바꾸지 마라. 이유: 부하 테스트는 호스트 jar로 띄우며 평소 개발 구성에는 영향을 주지 않는다.
- 실행 로그 전체를 표준 출력에 내보내지 마라. 이유: 출력은 요약만 남기고 상세는 실행 디렉터리에 둔다(AGENTS.md § 9).
- 같은 디렉터리에서 다른 Gradle 검사와 동시에 측정을 돌리지 마라. 이유: CPU 경합으로 수치가 흔들린다(AGENTS.md § 7).
