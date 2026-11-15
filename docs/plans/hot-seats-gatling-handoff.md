# Gatling hot-seats 실행 인계

- 작성일: 2026-11-15
- 상태: 사용자 요청으로 측정을 중단하고 다음 세션으로 인계한다. **300ms 달성·독립 실행 5회·Scouter ON/OFF 비교는 미완료**다.
- 원래 범위와 판정 계약: [실행 계획](./hot-seats-gatling-p95-300ms.md)
- 전용 대시보드와 지표: [대시보드 안내](../performance/gatling-hot-seats-dashboard.md)

## 이번에 구현·확인한 범위

1. Gatling `--warmup --warmup-rounds N`, `--booking-p95-ms N` 옵션을 추가했다. 예열마다 새 Gatling JVM·회차를 쓰고 로그·원시 결과·준비 현황·요약·판정을 별도 디렉터리에 남긴다. 앱 JVM은 예열과 본 측정 사이에 유지한다.
2. 생성 성공 2xx / 정확히 409 `SEAT_ALREADY_HELD` / 429 / 기타 응답의 표본 수와 p50·p95·p99를 추가했다. Gatling 응답의 시작·완료 시각으로 기록한다. 전체 표본은 실패까지 포함한 원시 로그에서 읽는다.
3. Gatling 3.16.0 바이너리 로그 집계기를 추가했다. 버전·잘린 레코드·알 수 없는 레코드를 거부한다. 기존 원시 결과에서 p95 2,281ms가 재현됐다. 버전을 올리면 집계기를 먼저 검토해야 한다.
4. 별도 `verdict.json`에서 준비·SSE·응답 표본·오류·확정 완료·서버 차분·통제 입력·격리·전체/성공 p95를 판정한다. 예열은 지연 미달만 허용한다. 본 측정 미달은 러너를 비정상 종료시킨다. 누락 파일·성공 표본 0건을 합격시키지 않는다.
5. 기존 Overview 파일을 유지하고 별도 UID의 Grafana 대시보드와 스크립트 결과 exporter를 추가했다. Grafana API에서 기존 35개·신규 41개 패널, Prometheus에서 exporter `up=1`과 실행 정보 수집을 확인했다. 브라우저 접근 호스트를 사용할 수 없어 화면 렌더링 검수는 하지 못했다.
6. 중단 시 TERM trap이 정리 후 다음 반복을 시작하던 문제를 확인했다. INT/TERM은 종료 코드를 반환하고 EXIT에서 정리하도록 바꿨다. TERM 뒤 다음 반복을 실행하지 않는 회귀 테스트를 추가했다.

서비스의 예매·멱등·DB·Redis 설계나 서버 구현은 바꾸지 않았다. Gatling 보상 시나리오의 러너 연결은 아직 지원하지 않는다. 다음 세션에서 2단계 실행·원장·환불 검증을 연결한 뒤 검증해야 한다.

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
