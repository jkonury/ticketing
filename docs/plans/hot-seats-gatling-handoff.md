# Gatling hot-seats 실행 인계

- 작성일: 2026-11-15
- 상태: 장기 과제로 보류(로드맵 후속 항목). 사용자 요청으로 측정을 중단하고 다음 세션으로 인계한다. **300ms 달성·독립 실행 5회·Scouter ON/OFF 비교는 미완료**다.
- 원래 범위와 판정 계약: [실행 계획](./hot-seats-gatling-p95-300ms.md)
- 전용 대시보드와 지표: [대시보드 안내](../performance/gatling-hot-seats-dashboard.md)

## 이번에 구현·확인한 범위

1. Gatling `--warmup --warmup-rounds N`, `--booking-p95-ms N` 옵션을 추가했다. 예열마다 새 Gatling JVM·회차를 쓰고 로그·원시 결과·준비 현황·요약·판정을 별도 디렉터리에 남긴다. 앱 JVM은 예열과 본 측정 사이에 유지한다.
2. 생성 성공 2xx / 정확히 409 `SEAT_ALREADY_HELD` / 429 / 기타 응답의 표본 수와 p50·p95·p99를 추가했다. Gatling 응답의 시작·완료 시각으로 기록한다. 전체 표본은 실패까지 포함한 원시 로그에서 읽는다.
3. Gatling 3.16.0 바이너리 로그 집계기를 추가했다. 버전·잘린 레코드·알 수 없는 레코드를 거부한다. 기존 원시 결과에서 p95 2,281ms가 재현됐다. 버전을 올리면 집계기를 먼저 검토해야 한다.
4. 별도 `verdict.json`에서 준비·SSE·응답 표본·오류·확정 완료·서버 차분·통제 입력·격리·전체/성공 p95를 판정한다. 예열은 지연 미달만 허용한다. 본 측정 미달은 러너를 비정상 종료시킨다. 누락 파일·성공 표본 0건을 합격시키지 않는다.
5. 기존 Overview 파일을 유지하고 별도 UID의 Grafana 대시보드와 스크립트 결과 exporter를 추가했다. Grafana API에서 기존 35개·신규 41개 패널, Prometheus에서 exporter `up=1`과 실행 정보 수집을 확인했다. 브라우저 접근 호스트를 사용할 수 없어 화면 렌더링 검수는 하지 못했다.
6. 중단 시 TERM trap이 정리 후 다음 반복을 시작하던 문제를 확인했다. INT/TERM은 종료 코드를 반환하고 EXIT에서 정리하도록 바꿨다. TERM 뒤 다음 반복을 실행하지 않는 회귀 테스트를 추가했다.

서비스의 예매·멱등·DB·Redis 설계나 서버 구현은 바꾸지 않았다. Gatling 보상 시나리오의 러너 연결은 이 시점에는 지원하지 않았고, 이후 연결해 검증했다(아래 "queue-surge·보상 회귀" 절).

## 검사 결과와 한계

| 검사 | 결과 |
|---|---|
| `./scripts/agent/test/run-tests.sh` | 전체 테스트 통과 |
| `./scripts/agent/test/check-summary.sh` | 통과 |
| `./scripts/agent/test/check-summary.sh ktlintCheck detekt` | 통과 |
| `./gradlew ktlintFormat :loadtest-gatling:gatlingDist` | 통과, 배포 디렉터리 생성 |
| Python 집계·판정·exporter·TERM 회귀 테스트 | 11개 통과. 아래 재실행 명령 참고 |
| `bash -n scripts/loadtest/run.sh` | 통과 |
| `./scripts/e2e/run.sh` | 첫 실행은 Buildx 쓰기 권한으로 실패. `BUILDX_CONFIG=/tmp/ticketing-buildx`로 재실행해 통과 |
| 관측 Compose 기동 | loadtest-exporter·Prometheus·Grafana 정상 기동 |
| 실제 Gatling 예열 | 첫 예열 1회 유효. 본 측정은 미실행 |

검사 후 셸 종료 처리·관측 코드·문서가 추가됐다. 마지막 상태의 Python 회귀·셸 구문·문서 검사와 커밋 훅 품질 검사를 별도로 확인한다. 전체 테스트·E2E 통과가 아직 실행하지 않은 Gatling 본 측정·보상 러너 통합 검증을 대신하지 않는다.

```bash
./scripts/agent/test/run-summary.sh -- python3 -m unittest discover -s scripts/loadtest -p 'test_*.py'
./scripts/agent/test/run-summary.sh -- bash -n scripts/loadtest/run.sh
./scripts/agent/docs/check-docs.sh
```

## 중단한 실행과 산출물

실행 루트: 로컬 `build/loadtest/runs/20261115-180626-hot-seats/`. 코드 수정 중 확인 실행이며 기준선으로 채택하지 않는다. 비밀 값과 대용량 결과는 커밋하지 않는다.

- 명령: 기존 CPU 배치·Scouter OFF·관측 ON·5회 예열·독립 실행 3회 계획이었다.
- 실행 1의 첫 예열: 정시 준비·등록·입장·예매 시도·실제 예매 HTTP 각각 1,000명/건, 지각 0, 429·기타 응답 0, 전체 HTTP 실패 0, 생성·결제·확정 예매 60건, 정상 409 940건이었다. 정합성·settle·예열 판정은 통과했다.
- 첫 예열 지연: 전체 p50/p95/p99 **1,157/1,635/1,700ms**, 성공 **481/1,484/1,736ms**, 정상 409 **1,205/1,637/1,700ms**. 콜드에 가까운 예열 표본이며 본 측정 300ms 결과가 아니다.
- 실행 1의 둘째 예열 도중 중단했다. 요약 누락으로 예열 무효가 남았다. 이전 TERM trap 때문에 실행 2가 시작돼 러너를 강제 종료하고 해당 실행 경로의 JVM들에 TERM을 보냈다. 실행 2 역시 불완전하므로 비교에서 제외한다.
- 래퍼 종료 코드 137, 로그 `/tmp/ticketing-command.dUwaJ7kx`. 첫 예열 결과는 실행 1 아래 `warmup-1/`, settle 결과는 `warmup-settle-1.json`이다. 최초 포트 충돌 시도는 `/tmp/ticketing-command.kxd14n6Z`에 남았다.
- 기존 E2E가 남긴 Compose fake-pg가 18090을 점유해 부하 실행 전에 해당 서비스만 정지했다. 다음 세션에서도 18000·18080·18081·18090이 비어 있는지 확인한다.
- 관측 스택·DB·Redis는 유지한다. DB·Redis 전체 초기화는 하지 않았다.

## 다음 세션 순서

1. 이 문서, 원래 계획, 로드맵을 읽고 git 상태·점유 포트·실행 프로세스를 확인한다. 중단된 디렉터리의 `dashboard.prom`은 정리할 필요가 있으면 원본 로그를 남기고 `interrupted` 단계로 재발행한다. exporter의 시작/중단 상태만으로 완료를 추정하지 않는다.
2. **러너 통합 검증을 먼저 끝낸다.** 본 측정 성공·실패 모두에서 `verdict.json`, 서버 차분·통제 입력·격리·정합성 보존과 최종 종료 코드를 확인한다. 예열 settle 실패·준비 부족·429·지표 누락·assertion 미달·성공 표본 없음이 실제 러너에서도 실패하는지 확인한다. 현재는 집계·판정 단위 테스트와 첫 정상 예열까지만 검증했다.
3. dashboard의 모든 PromQL 실행과 브라우저 레이아웃을 확인한다. 실행 선택은 스크립트 결과에만 적용된다. 서버 실시간 패널은 해당 실행 시간대로 별도 설정한다.
4. 기존 배치에서 5회 예열 3회와 15회 예열 3회 기준선을 **새로** 측정한다. 아래 명령의 결과가 미달 종료여도 실패 데이터를 보존한다. 다른 Gradle 검사·부하와 병행하지 않는다.
5. 원래 계획의 CPU 격리·병목 진단 채택 조건을 따른다. 새 근거 없이 서버 설정·설계를 바꾸지 않는다. 최종 OFF는 15회 예열 후 독립 실행 5회 모두 전체/성공 p95 < 300ms인지 판정한다.
6. 사용자 추가 요청: **최종 동일 코드·CPU·관측·15회 예열에서 Scouter ON/OFF 각각 최소 3회 교대 실행**한다. OFF 최종 5회와 비교 계열을 명시적으로 구분하고 ON도 동일 300ms 기준의 성공/실패를 그대로 기록한다. 패치 에이전트와 모듈별 설정·해시·collector 연결을 확인한다. ON은 `--no-scouter`를 빼되 실제 에이전트 연결을 확인해야 한다. 기존 콜드 비교 문서의 결과와 합쳐 개선율을 계산하지 않는다.
7. queue-surge 전후 동일 조건 각 3회와 Gatling 보상 러너 연결·정합성 검증을 완료한다. 새 최종 보고서와 ON/OFF 비교를 M5 허브에 연결하고, 최종 판정 후에만 로드맵의 목표 항목을 체크한다.

```bash
# A: 각 명령은 앱 새 기동 → 예열 → 본 측정을 3회 수행한다.
./scripts/loadtest/run.sh --scenario hot-seats --tool gatling --gatling-heap 2g --runs 3 --observability --no-scouter --warmup --warmup-rounds 5 --booking-p95-ms 300 --k6-cpus 0-3 --app-cpus 4-15
./scripts/loadtest/run.sh --scenario hot-seats --tool gatling --gatling-heap 2g --runs 3 --observability --no-scouter --warmup --warmup-rounds 15 --booking-p95-ms 300 --k6-cpus 0-3 --app-cpus 4-15

# E2E에서 읽기 전용 홈의 Buildx 활동 기록 오류가 재현될 때
mkdir -p /tmp/ticketing-buildx
BUILDX_CONFIG=/tmp/ticketing-buildx ./scripts/e2e/run.sh
```

## 구현 시 확인한 공식 근거

- [Gatling HTTP response 변환](https://docs.gatling.io/reference/script/http/request/#transformresponse): 응답의 시작·완료 시각을 사용한다.
- [Gatling assertion](https://docs.gatling.io/concepts/assertions/): 종료 시 전체 통계 판정을 수행한다.
- [3.16.0 로그 직렬화 소스](https://github.com/gatling/gatling/blob/v3.16.0/gatling-core/src/main/scala/io/gatling/core/stats/writer/LogFileDataWriter.scala): 바이너리 형식 기준.
- [Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/), [Prometheus 지표 형식](https://prometheus.io/docs/instrumenting/exposition_formats/): 별도 UID·지표 파일 발행 기준.

## 2026-11-15 이후 측정 결과(독립 실행, 코드·서버 설정 변경 없음)

모든 실행은 기존 Scouter OFF·관측 ON·Gatling 힙 2g·`--booking-p95-ms 300`이며 전부 300ms 미달 종료다. 실패 실행도 `build/loadtest/runs/`에 보존했다. 최종 판정(D)·ON/OFF·queue-surge·보상 러너는 아직 하지 않았다.

| 단계 | 조건 | 유효 실행 전체 p95(ms) | 중앙값 | 비고 |
|---|---|---|---|---|
| A | 기존 배치, 5회 예열 | 512, 534, 505 | 512 | 성공 p95 401~474 |
| A | 기존 배치, 15회 예열 | 328, 392, 368 | 368 | 예열 5→15에서 약 28% 감소 |
| B | 기존 배치, 15회 예열 | 400, 350, 344 | 350 | |
| B | SMT 분리 배치, 15회 예열 | 336, 384, 389 | 384 | 기존 대비 약 10% 증가. **기각**, 기존 배치 유지 |

- B의 무효 실행 4건(`210002`·`210453`·`212345`·`212516`)에서 Gateway→core(18080) `Connection reset by peer(-104)`로 예매 생성 요청이 오류가 됐다(예열 오류율 1% 초과 또는 Gateway·core 건수 1~2건 불일치). 앞의 8회에는 없었고 이후 2회도 0건이다. core 로그에는 오류가 없고 Gateway 풀은 `max-idle-time: 20s`다. **원인 미확인**이며 최종 판정 실행에서 재발하면 별도 진단한다.
- 같은 1,000건 `booking_create` 평균 분해(기존 배치 15회 예열 본 측정 `20261115-213031`): Gateway route 138ms = jwt 0.5 + admission-body 14.0 + admission-redis 22.4 + ratelimit-redis 41.2 + proxy 61.6. 이 proxy 안에 core 처리 32ms가 포함된다. Gatling p50은 260ms, Gateway route p50은 142ms로 클라이언트~route 시작 사이에 약 118ms가 더 있다(발생기·수락·이벤트 루프 대기 포함, 구성 미분해).
- Redis 서버 비용은 작다(`EVALSHA` 평균 약 24µs, 입장 토큰 해시는 작은 해시). 10ms 이상 Lua는 실행당 1회 정도이며 본 측정 구간과 겹치지 않았다. 따라서 Redis 2회 왕복 합 약 64ms는 서버 연산보다 Gateway 쪽(이벤트 루프·Lettuce 대기)일 가능성이 있으나 **미검증 가설**이다.
- 다음 진단 후보(근거 확보 전 서버 코드·설정 변경 금지): 폭주 1~2초 동안 Gateway 프로세스·스레드별 CPU와 Lettuce/Netty 이벤트 루프 대기를 고빈도로 수집하는 별도 진단 계열(JFR 포함). 발생기 구간 118ms도 같은 요청의 Gatling 발송·완료 시각과 Gateway 수신 시각으로 분해한다.

### 병목 진단(JFR, 기존 배치, 15회 예열) 결과

JFR 진단 실행 `20261115-221926`(`--jfr`, 전체 p95 366ms)은 최종 판정 계열과 섞지 않는다. 같은 조건 대조 3회(`20261115-223904`, 발생기 CPU 0-5·앱 6-15)의 전체 p95는 1,255 / 428 / 390ms로 기존 배치 중앙값 350ms보다 나빴다.

- 1,000건 `booking_create`는 첫 163ms 안에 모두 발송되고(536건이 첫 50ms) 시작 시점과 무관하게 지연이 약 290ms로 같다. p95는 개별 요청 비용이 아니라 1,000건 폭주의 처리 완료 시간(약 0.35~0.43초)이 결정한다.
- 폭주 1초 평균 JVM CPU: gateway 1.25, core 1.03, fake-pg 0.39코어(합 약 2.7). 앱용 12코어 대부분이 1초 평균으로는 비어 있다. 외부 예열 후 core CPU는 콜드 JFR 추정(약 8코어)보다 훨씬 작다. 1초 평균이라 sub-second 포화는 이 자료로 판단할 수 없다.
- Gateway는 폭주 구간 실행 샘플이 68개뿐이다(요청당 CPU 약 1ms 미만). Gateway 안의 Redis 2회 왕복 합 약 64ms는 계산 시간이 아니라 대기다. Gatling GC 정지(2.5ms 1회)와 서버 GC 정지(실행당 합 14~17ms)는 원인이 아니다.
- 발생기 CPU를 4→6코어로 늘리고 앱을 12→10코어로 줄이면 악화됐다. 발생기 코어 부족 가설은 지지되지 않고, 폭주 순간 앱은 코어 수에 민감하다. 이 대조는 발생기·앱 배분이 함께 바뀐 환경 비교이며 서버 코드 효과가 아니다.
- 아직 설명하지 못한 것: 앱 CPU 평균이 낮은데 폭주 처리에 0.4초가 걸리는 이유, 클라이언트~Gateway route 시작 사이 약 118ms, 간헐적 1.2초 튐(`223904/1`), 연결 리셋. 다음 진단은 sub-second 단위의 스레드별 CPU 대기(run queue)·JIT 상태 수집이다. 근거 없이 서버 설정·코드를 바꾸지 않는다.

### 최종 판정 D와 Scouter ON/OFF (2026-11-16 확인, 서버 코드·설정 변경 없음)

모든 실행은 기존 CPU 배치(발생기 0-3 / 앱 4-15)·15회 예열·관측 ON·Gatling 힙 2g이며 **전부 300ms 미달**이다. 정합성·준비 인원·429·SSE는 통과했고 미달은 p95 항목이다.

| 계열 | 전체 p95(ms) | 중앙값 | 비고 |
|---|---|---|---|
| D 최종 판정(OFF) 5회 `20261115-233505` | 403, 374, 386, 306, 391 | **386** | 5회 모두 미달. 4번만 성공 p95(277) 통과. 1번 p99 1,277ms |
| OFF 교대(공식 ON과 교대) `20261116-010610`·`014235`·`021904` | 355, 301(무효 `warmupDelta`), 316 | – | 공식 에이전트 ON과 교대한 계열 |
| ON, 공식 에이전트 2.21.3(SHA `fc0c1ead…`) `012419`·`020047`·`023712` | 421, 462, 540 | 462 | **패치 아님**. `.env`의 에이전트가 사용됨. 별도 계열로만 보존 |
| OFF 교대(패치 ON과 교대) `025553`·`033216`·`040839` | 421, 368, 389(무효 `warmupDelta`) | – | |
| **ON, 패치 에이전트** `031403`·`035024`·`042648`(SHA `ac30c33b…`, `[R171]` 2건씩 확인) | 435, 535, 414 | **435** | 성공 p95 425, 478, 384 |

- 유효 OFF 표본은 실행 간 흩어짐이 크다(전체 p95 301~421). 패치 ON 435ms는 같은 시기 OFF 368~421ms 대비 높지만 3회씩으로 비용을 정량화하지 않는다. 콜드 계열 결과와 섞어 개선율을 계산하지 않는다.
- `warmupDelta` 무효는 예열 누적 Gateway·core 건수가 1~8건 어긋난 경우로, 모두 게이트웨이→core 연결 리셋(`상대편이 연결을 끊음`) 로그와 함께 나타났다. 원인은 여전히 미확인이다. 무효 표본은 보존하고 판정에 쓰지 않았다.
- ON 실행의 CPU·GC·메모리 비교는 아직 정리하지 않았다.

### queue-surge·보상 회귀 (2026-11-16, 서버 코드·설정 변경 없음)

- queue-surge Gatling 3회(`20261116-044521-queue-surge`, 기존 배치·OFF): 모두 통과. 중복 판매 0·Redis 불일치 0·5,000석 확정, 예매 생성 p95 26/43/90ms. 서버 변경이 없어 "전후" 비교는 없고 현 코드의 기록이다.
- 보상(`seat-conflict-refund`) Gatling 러너 연결: `scripts/loadtest/run.sh`에서 첫 단계를 Gatling `PHASE=first`로 실행하고(`first-gatling/`), 본 단계는 `PHASE=second`로 실행한다. `judge`는 `compensation.json`의 `ok`를 `compensation` 검사로 판정하고 파일 누락은 `requiredArtifacts` 실패다. 회귀 테스트를 추가했다.
- 연결 중 기존 결함을 발견해 고쳤다. `verify-compensation.sh`가 PG 거래를 UTC 날짜로 조회해, 한국 시각 00~09시에는 fake-pg(Asia/Seoul 기준)의 오늘 거래가 비어 쌍별 PG 검사 3개(`pgWinnerApprovedOnce`·`pgLoserRefundedOnce`·`pgTxnMatchesPayment`)가 실패했다. Asia/Seoul 날짜로 바꿨다. 수정 전 실행 `20261116-050028`은 실패로, 수정 후 `20261116-050237`은 통과(쌍 100, A 승 57·B 승 43, 확정 100, `SEAT_ALREADY_SOLD` 취소 100, 환불 100, 중복 환불 0)로 보존했다.
