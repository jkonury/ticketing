# Gatling Scouter APM ON/OFF 성능 비교

측정일: 2026-11-15. 동일 코드에서 `queue-surge`·`hot-seats`를 Scouter ON/OFF 각각 3회, 총 12회 실행했다. 원본은 로컬 `build/loadtest/runs/`에 보존한다.

## 결론

- queue-surge는 ON에서 전체 처리 시간이 중앙값 **374초**로 OFF **255초**보다 **+46.7%** 길었다. 전체 완료 인원 기준 평균 처리율은 **-31.8%**, 앱 CPU 시간 합계는 **+98.0%**, GC pause 합계는 **+360.1%**였다.
- hot-seats 예매 생성 p95는 OFF **1,855ms** → ON **2,607ms**, **+40.5%**였다. ON/OFF 모두 3회 전부 임시 1,500ms 기준 미달이며, 후속 목표인 300ms도 미달이다.
- 전 실행 요청 실패율 0%, SSE 연결 성공률 100%, 전원 입장, 중복 판매·DB/Redis 불일치 0건이다. hot-seats는 6회 모두 정시 준비·예매 시도 1,000명이다. ON 6회에서 OOM은 관측되지 않았다.
- 현재 경량 설정에서도 이 부하에서 Scouter ON 비용이 관측된다. 성능 기준선은 OFF로 분리하고 ON 결과는 진단 계열로 관리한다. 특정 훅을 원인으로 지목하거나, 과거 설정 대비 OOM 개선율을 주장할 근거는 이번 비교에 없다.
- 후속 실험에서 SSE 경로를 XLog에서 제외하고 코루틴 훅을 끄면(F) queue-surge의 ON 시간 -12.9%·CPU -21.4%·GC -47.5%였다. OFF 대비로는 CPU +55.5%가 남았다. 자세한 내용은 [후속 실험](#후속-실험-reactor코루틴-훅-비용-분리-queue-surge)에 있다.
- 남은 비용의 원인은 Scouter lift가 Reactor 자동 컨텍스트 전파와 겹쳐 모든 연산자에서 ThreadLocal 복원을 일으키는 것이었다. txid 전파를 context-propagation에 맡기는 패치 에이전트(C)는 reactive 훅을 켠 채로 queue-surge 앱 CPU가 OFF와 같았고(851.9 vs 851.8 CPU초), hot-seats 예매 p95는 OFF 대비 +23.0%(기존 ON +40.5%)였다. C를 채택한다([후속 실험](#후속-실험-txid-전파-방식-교체-패치-에이전트), [ADR 0027](../adr/0027-scouter-reactive-txid-context-propagation.md)).

## 실행 조건

- 코드: HEAD `41ab458`, 측정 중 코드·설정 변경 없음(`source.patch` 12개 모두 비어 있음). AMD Ryzen 7 5700G, 논리 16코어, 메모리 약 27GiB, Java 25.0.5, Gatling 3.16.0.
- 기본 규모: queue-surge 10,000명·5,000석·입장 배치 50명, hot-seats 1,000명·핫 좌석 100석·입장 배치 1,000명·시뮬레이션 시작 60초 후 경합. 매회 실제 시드 5,000석·핫 100석 확인.
- 부하 도구 CPU `0-3`, 앱 CPU `4-15`. Gatling 힙 2g, core 2g·realtime 1g·gateway 1g·fake-pg 512m. Hikari 최대 풀 10, rate limit 활성, 결제 실패·중복 주입 없음.
- 매회 앱 JVM 새 기동, 사전 워밍업·AOT·JFR 없음. 동일 공연장을 재사용하되 새 회차·좌석 사본·멱등 키로 실행. DB·Redis 전체 초기화 없음. 최초 공연장 캐시 파일만 백업해 5,000석을 새로 시드했다.
- 양쪽 모두 `--observability`: Prometheus 5초 scrape, Micrometer/OTel 추적 sampling 0.1 유지. OFF는 Scouter 에이전트만 제거한다. HTML 리포트 생성은 비활성으로 동일하다.
- ON은 runner가 core·realtime·gateway·fake-pg 네 JVM 모두에 Scouter 2.21.3 java21plus 에이전트를 연결한다. Gateway·realtime은 각 전용 설정, core·fake-pg는 공통 설정을 쓴다. WebFlux 설정은 HTTP XLog 수집도 제한하는 현재 설정이므로 Scouter 전체 계측 기본값의 비용으로 일반화하지 않는다.
- Scouter 에이전트 SHA-256: `fc0c1ead3b314f5da542c790f148c1ee264e597dce8c75dfe2ddd4f6b9472311`. 설정 파일 해시·Java 버전은 로컬 `build/loadtest/scouter-comparison-20261115/metadata.json`에 보존.
- 순서: 반복 1은 A ON → A OFF → B ON → B OFF, 반복 2는 A OFF → A ON → B OFF → B ON, 반복 3은 반복 1과 동일. 다른 Gradle 검사·부하와 병행하지 않았다.

```bash
./scripts/loadtest/run.sh --scenario queue-surge --tool gatling --observability
./scripts/loadtest/run.sh --scenario queue-surge --tool gatling --observability --no-scouter
./scripts/loadtest/run.sh --scenario hot-seats --tool gatling --observability
./scripts/loadtest/run.sh --scenario hot-seats --tool gatling --observability --no-scouter
```

각 명령을 위 순서로 3회씩 실행했다. Scouter ON은 기존 `.env`의 에이전트·collector 설정을 사용했고, 매회 `env.txt`와 ON 앱의 Scouter 기동 로그를 대조했다.

## 3회 중앙값과 범위

표는 **중앙값 (최소~최대)**이며, 변화율은 `(ON 중앙값 / OFF 중앙값 - 1) × 100`이다. p95는 각 실행의 Gatling 전체 분포 p95를 다시 중앙값으로 요약했으며 3회를 합친 p95가 아니다.

| 시나리오 | 지표 | OFF | ON | ON 변화율 |
|---|---|---:|---:|---:|
| queue-surge | 예매 생성 p95 (ms) | 28 (23~50) | 20 (20~22) | -28.6% |
| queue-surge | 전체 측정 시간 (초) | 255 (249~255) | 374 (368~382) | +46.7% |
| queue-surge | 앱 CPU 시간 합계 (CPU초) | 851.8 (840.3~865.4) | 1,686.4 (1,626.9~1,746.2) | +98.0% |
| queue-surge | 평균 앱 CPU 사용 (코어 환산) | 3.37 (3.34~3.39) | 4.51 (4.42~4.57) | +33.6% |
| queue-surge | 앱 GC pause 합계 (초) | 5.746 (5.627~6.166) | 26.439 (23.919~27.290) | +360.1% |
| queue-surge | 전체 완료 처리율 (명/초) | 39.22 (39.22~40.16) | 26.74 (26.18~27.17) | -31.8% |
| queue-surge | 대기열 대기 p95 (초) | 241.2 (236.0~242.6) | 359.3 (350.3~367.6) | +49.0% |
| queue-surge | 입장 스케줄러 평균 간격 (초) | 1.244 (1.220~1.247) | 1.816 (1.797~1.852) | +46.0% |
| hot-seats | 예매 생성 p95 (ms) | 1,855 (1,726~1,979) | 2,607 (2,490~3,052) | +40.5% |
| hot-seats | 전체 측정 시간 (초) | 66 (66~66) | 66 (66~66) | +0.0% |
| hot-seats | 앱 CPU 시간 합계 (CPU초) | 94.8 (92.4~97.1) | 120.1 (113.2~122.8) | +26.7% |
| hot-seats | 평균 앱 CPU 사용 (코어 환산) | 1.44 (1.40~1.47) | 1.82 (1.71~1.86) | +26.7% |
| hot-seats | 앱 GC pause 합계 (초) | 0.299 (0.278~0.316) | 0.299 (0.294~0.303) | +0.0% |

전체 측정 시간은 Gatling JVM 실행 직전부터 종료까지다(앱 빌드·기동, 측정 후 정합성 검사 제외). hot-seats는 60초 동기화 대기가 포함되므로 이 시간으로 순간 경합 처리량을 판단하지 않는다. queue-surge 처리율은 10,000명 / 전체 측정 시간으로, 최대 수용 용량 측정값이 아니다.

CPU 시간은 측정 직전·직후 각 앱 `process_cpu_time_ns_total` 차분의 합이며, 평균 코어 환산은 이를 전체 측정 시간으로 나눈 값이다. GC pause도 직접 수집 차분을 앱별로 합한 값이므로 단일 JVM의 최장 중단이나 호스트 전체 중단 시간을 뜻하지 않는다.

## 앱별 CPU·GC·힙

3회 중앙값. 힙은 Prometheus 5초 표본의 실행별 최대 사용량을 요약해 순간 최대를 놓칠 수 있다. 할당률이나 누수 판정값으로 쓰지 않는다.

| 시나리오 | 앱 | CPU초 OFF → ON | GC초 OFF → ON | 힙 표본 최대 MiB OFF → ON |
|---|---|---:|---:|---:|
| queue-surge | core | 133.2 → 142.2 | 0.386 → 0.163 | 1,316 → 1,324 |
| queue-surge | realtime | 415.3 → 946.3 | 3.409 → 19.255 | 842 → 914 |
| queue-surge | gateway | 281.5 → 574.7 | 1.964 → 6.968 | 968 → 969 |
| queue-surge | fake-pg | 21.8 → 23.8 | 0.109 → 0.067 | 327 → 337 |
| hot-seats | core | 29.4 → 31.4 | 0.133 → 0.000 | 1,260 → 1,210 |
| hot-seats | realtime | 22.3 → 32.3 | 0.000 → 0.063 | 468 → 480 |
| hot-seats | gateway | 40.0 → 50.5 | 0.103 → 0.220 | 448 → 567 |
| hot-seats | fake-pg | 4.0 → 5.5 | 0.039 → 0.011 | 300 → 304 |

## 실행별 결과

실행 ID는 로컬 `build/loadtest/runs/` 아래 디렉터리이며 각 결과는 그 아래 `1/`에 있다. CPU는 네 앱 합계다.

| 실행 ID | 시나리오 | APM | 반복 | p95 ms | 측정 초 | CPU초 | 입장 | 확정 예매/좌석 |
|---|---|---|---:|---:|---:|---:|---:|---:|
| 20261115-113408-queue-surge | queue-surge | ON | 1 | 20 | 368 | 1,626.9 | 10,000 | 2,065/5,000 |
| 20261115-114115-queue-surge | queue-surge | OFF | 1 | 50 | 249 | 840.3 | 10,000 | 2,048/5,000 |
| 20261115-114606-hot-seats | hot-seats | ON | 1 | 2,607 | 66 | 120.1 | 1,000 | 61/99 |
| 20261115-114757-hot-seats | hot-seats | OFF | 1 | 1,979 | 66 | 94.8 | 1,000 | 63/99 |
| 20261115-114944-queue-surge | queue-surge | OFF | 2 | 23 | 255 | 865.4 | 10,000 | 2,020/5,000 |
| 20261115-115442-queue-surge | queue-surge | ON | 2 | 20 | 382 | 1,746.2 | 10,000 | 2,042/5,000 |
| 20261115-120149-hot-seats | hot-seats | OFF | 2 | 1,855 | 66 | 97.1 | 1,000 | 59/97 |
| 20261115-120337-hot-seats | hot-seats | ON | 2 | 3,052 | 66 | 122.8 | 1,000 | 63/99 |
| 20261115-120527-queue-surge | queue-surge | ON | 3 | 22 | 374 | 1,686.4 | 10,000 | 2,077/5,000 |
| 20261115-121226-queue-surge | queue-surge | OFF | 3 | 28 | 255 | 851.8 | 10,000 | 2,087/5,000 |
| 20261115-121724-hot-seats | hot-seats | ON | 3 | 2,490 | 66 | 113.2 | 1,000 | 57/97 |
| 20261115-121913-hot-seats | hot-seats | OFF | 3 | 1,726 | 66 | 92.4 | 1,000 | 56/96 |

## 해석과 한계

- 요청 실패율은 Gatling KO 기준이다. 좌석 경합 409·rate limit 429는 시나리오의 기대 응답으로 KO에 포함하지 않는다. 예매 생성 p95에도 기대 응답이 포함되므로 확정 예매만의 응답 시간이 아니다.
- queue-surge의 ON 예매 p95가 OFF보다 낮더라도 전체 처리 성능 개선으로 해석하지 않는다. ON은 입장 간격·전체 대기 시간이 늘었고, 예매 요청에 도달하는 시점과 재시도 횟수·성공 예매 수가 달라진다. 동일 인원 조건에서 대기열 처리와 API 지연을 함께 읽어야 한다.
- hot-seats는 6회 모두 1,000명 전원 정시 준비·예매 시도 조건을 충족해 고정 시각 경합 비교가 성립한다. 동일 부하에서 ON p95와 CPU 비용이 늘어나는지 위 3회 수치로 판단한다.
- 한 호스트에서 앱·DB·Redis·발생기·관측 스택을 공유한다. 실행 순서는 교차했지만 완전 무작위 배치는 아니며, 좌석 선택 난수 시드는 고정하지 않았다. OS/DB 캐시·기존 데이터 증가도 통제하지 않았다. 표본 3회로 통계적 유의성이나 다른 하드웨어의 오버헤드를 주장하지 않는다.
- Gateway·realtime의 비용이 어디서 발생하는지는 프로파일링·훅별 대조 실험 없이 확정할 수 없다. 이번은 현재 Scouter ON/OFF의 총효과 측정이며 설정 튜닝 전후 비교가 아니다.
- 과거 k6 결과와 발생기·실행 세션이 달라 직접 개선율을 계산하지 않는다. 기존 M5의 k6 기준 판정은 [최종 보고서](m5/11-step8-final-report.md)를 그대로 유지한다.

## 후속 실험: Reactor·코루틴 훅 비용 분리 (queue-surge)

기본 비교에서 ON 비용이 gateway·realtime(WebFlux)에 집중돼, 두 앱의 Reactor·코루틴 훅과 SSE 경로를 바꿔 queue-surge를 3회씩 추가 실행했다. 코드·부하 조건은 위와 같고 ON(Scouter 4개 JVM 연결)만 다르다. 변경은 `deploy/scouter/ticketing-gateway.conf`·`ticketing-realtime.conf`에만 있다.

Scouter 2.21.3 소스 확인: `xlog_fully_discard_service_patterns`는 요청 시작 시점에 경로(쿼리 제외)를 판정하고, 일치하면 트랜잭션 컨텍스트를 만들지 않는다(`WebfluxHttpTrace.start`, `TraceMain.startHttp`). 컨텍스트가 없으면 Reactor `Context`에 `TraceContext`가 들어가지 않아 `TxidLifter`가 생성되지 않는다(`ReactiveSupport` `onEachOperator` 훅). `MonoKtASM`(코루틴)은 coroutine·reactive 훅이 모두 true일 때만 적용된다.

| 조합 | reactive | coroutine | SSE `/api/v1/streams/*` discard | 실행 수 |
|---|---|---|---|---:|
| OFF | 에이전트 없음 | - | - | 3 |
| ON(기존) | true | true | 없음 | 3 |
| E | true | true | 적용 | 3 |
| **F(채택)** | true | **false** | 적용 | 6 |
| D | **false** | false | 적용 | 3 |

| 조합 | 전체 측정 시간 (초) | 앱 CPU 합계 (CPU초) | 앱 GC pause 합계 (초) |
|---|---:|---:|---:|
| OFF | 255 (249~255) | 851.8 (840.3~865.4) | 5.75 (5.63~6.17) |
| ON(기존) | 374 (368~382) | 1,686.4 (1,626.9~1,746.2) | 26.44 (23.92~27.29) |
| E | 351 (346~355) | 1,437.6 (1,372.6~1,464.5) | 15.19 (14.29~16.38) |
| **F** | **326** (322~336) | **1,324.8** (1,280.8~1,363.0) | **13.89** (12.73~14.21) |
| D | 253 (248~902) | 852.0 (850.6~861.4) | 5.57 (5.39~5.86) |

F는 3회씩 두 묶음으로 실행했고 묶음별 중앙값이 시간 323·328초, CPU 1,323.0·1,356.7로 일치한다. 표의 F는 6회 중앙값이다. 앱별 6회 중앙값(CPU초 / GC초)은 realtime 704.1 / 8.10, gateway 466.5 / 5.39, core 131.5 / 0.13, fake-pg 21.1 / 0.05이다.

- ON(기존) 대비 F: 시간 -12.9%, CPU -21.4%, GC -47.5%. OFF 대비 F: 시간 +27.6%, CPU +55.5%, GC +141.7%.
- 기여 순서(연속 실행이라 근사): SSE discard가 CPU 약 15%, coroutine 훅 OFF가 추가 약 8%를 줄였다. reactive 훅을 끄면(D) CPU·GC가 OFF와 같아진다. 이 부하에서 남은 비용은 대부분 reactive 훅의 `TxidLifter`로 보이며, 메서드 프로파일·스케줄러 XLog 등 나머지 Scouter 기능 비용은 관측되지 않았다.
- **채택: F**(이후 [txid 전파 방식 교체](#후속-실험-txid-전파-방식-교체-패치-에이전트)의 C로 대체). HTTP XLog 수집은 유지하되 coroutine 훅을 끄고 SSE 경로를 XLog·TPS에서 제외한다. F 구성에서 HTTP XLog가 수집되는 것은 사용자가 수동 확인했다. 비용 잔존(CPU +55.5%)은 ON 계열이 진단용이라는 위 결론을 바꾸지 않는다.
- D는 3회 중 1회(`20261115-142700-queue-surge`)에서 Gatling 사용자 1명이 SSE 대기열 결과를 받지 못해 900초 상한에서 끝났다(`sse_queue_result` 9,999). 이 회차 서버 CPU·GC·정합성(5,000석 판매·중복 0)은 정상이라 시간 최댓값 902초는 서버 처리 시간이 아니다. 훅 OFF와의 관련 여부는 이 표본으로 판단할 수 없고 원인은 추적하지 않았다. 다른 모든 실행(E·F 포함)은 10,000건이었다.
- 한계: hot-seats는 재측정하지 않았다. 조합을 교차 배치하지 않고 연속 실행했다(순서: E → F1 → D → F2). SSE 판정 외 coroutine·reactive 훅의 요청별 비용은 분리하지 않았다. 표본 3~6회로 통계적 유의성을 주장하지 않는다.
- 실행 ID(`build/loadtest/runs/`, 각 `<ID>-queue-surge`): E `20261115-125630`·`130316`·`130957`, F `131828`·`132434`·`133048`·`144654`·`145301`·`145921`, D `141710`·`142203`·`142700`.

## 후속 실험: txid 전파 방식 교체 (패치 에이전트)

F에 남은 비용(OFF 대비 CPU +55.5%)의 원인을 JFR로 찾고, Scouter 소스를 패치해 txid 전파 방식만 바꿔 측정했다. 코드·부하 조건은 위와 같다. 에이전트는 Scouter 2.21.3과 같은 소스에 [패치](../../deploy/scouter/patches/scouter-2.21.3-reactive-txid-propagation.patch)를 적용해 빌드했고(`scouter.agent.jar` SHA-256 `ac30c33b28fb0e67af5f32cea8d915ffa384d15e515589d9010e969c2e172aec`), `SCOUTER_AGENT_DIR`로 네 JVM에 연결했다. 설정은 F에 `_hook_reactive_txid_propagation` 한 줄만 더했다.

### 원인

- F 구성 queue-surge 1회를 JFR로 기록했다(`20261115-152119-queue-surge`). gateway 실행 샘플의 25.6%가 `TxidLifter` 프레임을 지나지만 Scouter·lift 프레임이 최상위인 샘플은 수 %였다. 최상위 프레임은 `DefaultContextSnapshot$DefaultScope.close`·`ThreadLocalMap`·`SimpleObservation.notifyOnScopeOpened` 등 ThreadLocal 복원 비용이었다.
- Reactor 3.8.8 `Operators.LiftFunction.liftScannable`은 자동 컨텍스트 전파(`spring.reactor.context-propagation: auto`)가 켜져 있으면 lift되는 연산자마다 하위 subscriber를 `ContextWriteRestoringThreadLocalsSubscriber`로 감싼다. Scouter lifter가 subscriber를 그대로 돌려줘도 감싸므로, `Hooks.onEachOperator` 등록 자체가 모든 연산자의 신호마다 ThreadLocal 복원을 일으킨다. SSE discard로 트랜잭션이 없는 요청도 이 비용을 낸다.

### 조합과 결과

| 조합 | `_hook_reactive_txid_propagation` | 내용 |
|---|---|---|
| F | 없음(공식 에이전트, `lift`와 같음) | 연산자마다 `TxidLifter` |
| B | `lift_light` + `_hook_reactive_scannable_tracking_enabled=false` | lift 유지, 체크포인트 탐색·Context 재할당·scannable 추적 제거 |
| **C(채택)** | `context_propagation` | lift 미등록. Micrometer `ThreadLocalAccessor`(키 `TraceContext.class`)를 등록해 앱의 자동 전파가 txid를 복원 |

queue-surge (3~6회 중앙값, 범위):

| 조합 | 실행 수 | 전체 측정 시간 (초) | 앱 CPU 합계 (CPU초) | 앱 GC pause 합계 (초) | realtime CPU | gateway CPU |
|---|---:|---:|---:|---:|---:|---:|
| OFF | 3 | 255 (249~255) | 851.8 (840.3~865.4) | 5.75 (5.63~6.17) | 415.3 | 281.5 |
| F | 6 | 326 (322~336) | 1,324.8 (1,280.8~1,363.0) | 13.89 (12.73~14.21) | 704.1 | 466.5 |
| B | 3 | 330 (324~340) | 1,288.0 (1,281.6~1,308.5) | 12.84 (12.82~13.15) | 683.7 | 451.7 |
| **C** | 3 | **248** (247~249) | **851.9** (842.9~860.5) | **5.26** (5.09~5.31) | 416.2 | 278.7 |

hot-seats (3회 중앙값, 범위). ON은 기본 비교의 기존 설정이며 F는 hot-seats를 측정하지 않았다:

| 조합 | 예매 생성 p95 (ms) | 앱 CPU 합계 (CPU초) | 앱 GC pause 합계 (초) | gateway CPU | realtime CPU | core CPU |
|---|---:|---:|---:|---:|---:|---:|
| OFF | 1,855 (1,726~1,979) | 94.8 (92.4~97.1) | 0.299 | 40.0 | 22.3 | 29.4 |
| ON(기존) | 2,607 (2,490~3,052) | 120.1 (113.2~122.8) | 0.299 | 50.5 | 32.3 | 31.4 |
| **C** | 2,281 (1,972~2,381) | 99.6 (97.4~102.2) | 0.080 | 41.5 | 24.4 | 29.0 |

- C는 queue-surge에서 F 대비 시간 -23.8%·CPU -35.7%·GC -62.1%이고 OFF와 시간 -2.7%·CPU 0.0%로 차이가 없다. B는 F 대비 CPU -2.8%로 효과가 없어, 비용은 `TxidLifter` 내부가 아니라 lift 등록 자체에서 나온다는 위 분석과 일치한다.
- hot-seats에서 C는 CPU가 OFF 대비 +5.1%, 예매 p95는 +23.0%(ON 기존 +40.5%)다. p95 범위가 OFF와 일부 겹치고 표본이 3회라 남은 차이의 원인은 분리하지 않았다. 6회 모두 1,000명 정시 준비·예매 시도, 정합성 통과다.
- C에서 HTTP XLog와 Gateway Filter 메서드 스텝이 수집되는 것은 사용자가 수동 확인했다. 패치 에이전트 연결은 실행마다 gateway·realtime 로그의 `[R171]`로 확인했다.
- 한계: txid는 스케줄러 경계와 `handle`·`tap` 등 자동 전파 지점에서만 복원되므로 Netty 이벤트 루프에서 바로 발생하는 신호의 하위 스텝은 덜 연결될 수 있다. 하위 스텝 연결 수를 F와 정량 비교하지 않았다. Reactor 체크포인트 프로파일과 scannable 덤프는 C에서 쓸 수 없다. 조합은 연속 실행했다(JFR → C×3 → B×3 → hot-seats C×3).
- 실행 ID(`build/loadtest/runs/`): JFR `20261115-152119-queue-surge`, C `20261115-{153931,154422,154914}-queue-surge`, B `20261115-{155403,160010,160633}-queue-surge`, C hot-seats `20261115-{162616,162804,162952}-hot-seats`.

## 검증과 산출물

- 12회 모두 서버 지표 차분 `valid: true`, Gateway·core 예매 요청 수 통제 입력 검사 통과, 좌석 정합성 통과. queue-surge 6회 모두 10,000명 입장·5,000석 판매, hot-seats 6회 모두 1,000명 입장·정시 준비·예매 시도 및 핫 좌석 100석 이하 판매.
- Gatling assertion: queue-surge 6회 전부 통과. hot-seats 6회 전부 예매 p95 < 1,500ms 미달(종료 코드 2). 전체 실패율·SSE 연결 성공률은 12회 전부 통과. runner는 threshold 미달을 기록용으로 취급해 종료 코드 0이지만 이를 성능 합격으로 쓰지 않았다.
- 원본: 실행별 `env.txt`, `measurement.txt`, `gatling.log`, `gatling-summary.json`, `gatling-gc.log`, `gatling-results/`, `server-delta.json`, `prometheus.json`, `resources.csv`, `integrity.json`; hot-seats는 `readiness.json` 추가.
- 집계: 로컬 `build/loadtest/scouter-comparison-20261115/manifest.json`(순서·명령·종료 코드·경로), `comparison.json`(원본 추출값), `metadata.json`(환경), 실행별 runner 로그·집계 Python 스크립트. 빌드 산출물은 git에 넣지 않는다.
- 애플리케이션·시나리오 소스 변경이 없어 별도 단위 테스트·Kotlin 품질 검사는 실행하지 않았다. runner의 앱/Gatling 빌드와 실제 부하·정합성 검증을 수행했다.
