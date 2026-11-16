# M5 외부·내부 예열의 TCP·연결 상태 대조

> 허브: [M5-2 개선 및 측정 인계](../m5-improvements.md) · 이전: [실제 예매 구간 트래픽 혼합](18-warmup-traffic-mix.md)

## 중단 시점의 결과

사용자 요청으로 외부 3회차의 예열 도중 러너에 SIGTERM을 보내 중단했다(종료 코드 143). 외부 본 측정 2회만 완료했고 외부 3회차 본 측정·내부 예열 계열 3회는 수행하지 않았다. 따라서 **두 계열 차이의 진단은 미완료**다. 기존 내부 예열 문서의 760·772·750ms는 이번 TCP 계측으로 얻은 결과가 아니므로 같은 표에 합치지 않는다.

외부 2회는 전체 예매 p95 435·436ms로 300ms 기준에 미달했다. 폭주 구간의 ListenOverflows·ListenDrops·SYN 재전송·TCP timeout 증가가 모두 0이고 예매 지연 900~1,199ms 표본도 0건이다. 따라서 **이 두 회차의 p95를 accept 큐 넘침에 따른 약 1초 SYN 재전송으로 설명하는 가설은 지지되지 않는다.** 내부 계열과의 연결 수·Hikari 대기 차이는 아직 판단할 수 없다. 과거 연결 리셋의 원인도 확정하지 않았다.

## 측정 조건과 범위

HEAD `c60d9b2`의 앱 코드·운영 설정을 유지하고 러너·진단 보조 스크립트만 보완했다. Gatling 힙 2g, hot-seats 1,000명·5,000석·핫 좌석 100석, 관측 ON·Scouter OFF·JFR OFF, 발생기 CPU 0-3 / 앱 CPU 4-15, 예매 p95 기준 300ms다. 외부 계열은 매번 새 앱을 띄워 실제 hot-seats 15회 예열 후 본 측정하며, 내부 계열은 readiness 전 내부 예열 후 본 측정한다. 계획은 두 계열 순차 실행이었으나 외부 3회차 예열 중 사용자 요청으로 중단했다. JFR 진단과 순수 콜드는 요청에 따라 생략한다.

```bash
./scripts/loadtest/run.sh --scenario hot-seats --tool gatling --gatling-heap 2g \
  --runs 3 --observability --no-scouter --warmup --warmup-rounds 15 \
  --booking-p95-ms 300 --k6-cpus 0-3 --app-cpus 4-15

./scripts/loadtest/run.sh --scenario hot-seats --tool gatling --gatling-heap 2g \
  --runs 3 --observability --no-scouter --internal-warmup \
  --booking-p95-ms 300 --k6-cpus 0-3 --app-cpus 4-15
```

## 수집과 해석 범위

- 기존 `server-delta.json`·`resources.csv`·`resource-sampler.log`를 재사용한다. `scripts/loadtest/tcp-probe.py`는 본 부하 직전·직후 호스트 TCP 누적 카운터, 150ms 간격 연결 수·리슨 Recv-Q·SYN_RECV를 수집한다. Hikari 요약은 서버 차분이 만들어진 뒤 결합한다. `verdict.json` 판정은 변경하지 않았다.
- `tcp-pre.json`·`tcp-post.json`은 전체 구간 경계, `tcp-samples.csv`는 구간 내 표본, `tcp-delta.json`은 차분·폭주 직전 상태·직후 3초 요약이다. `booking-window.json`은 Gatling 원시 로그의 실제 첫 예매 시작·마지막 시작·마지막 종료 시각이다. 이전 JFR 분석의 p99 기반 추정 구간과 구분한다.
- TCP 카운터는 호스트 전역이다. 다른 부하가 없는 단독 실행을 전제로 하며, 관측 scrape·개발 도구·호스트의 다른 서비스 트래픽까지 완전히 배제하거나 포트별로 귀속할 수 없다. 전체 실행의 증가량만으로 예매 폭주에 원인을 귀속하지 않는다.
- 연결 수는 목적지 포트 18000·18080 기준의 클라이언트 측 ESTABLISHED 소켓 수다. 별도 클라이언트가 없다는 전제에서 각각 클라이언트→Gateway·Gateway→core로 해석한다. HTTP 요청이 진행 중인 수나 커넥션 풀의 활성·유휴 구분은 아니다.
- 표본 시각은 소켓 조회 시작 시각이다. IPv4·IPv6를 순차 읽으므로 원자적 스냅샷이 아니며, 표본보다 짧은 Recv-Q 첨두를 놓칠 수 있다. 표본 최대 간격과 폭주 직전 표본의 시차를 함께 남긴다. 3초 차분은 직전 표본부터 3초 이내 마지막 표본까지다.
- Hikari 획득 평균·p95는 전체 본 부하 동안의 모든 core DB 획득이다. 예매에만 귀속한 대기 시간이 아니며, p95는 히스토그램 버킷 보간값이다.
- 1초 부근 HTTP 지연 군집은 재전송의 단서일 뿐 SYN 재전송 증명이 아니다. [커널 TCP 카운터 설명](https://docs.kernel.org/networking/snmp_counter.html)과 [TCP 파라미터 설명](https://docs.kernel.org/networking/ip-sysctl.html)에 따라 accept backlog와 SYN_RECV 한도를 구분한다. 포트별 패킷 관측이 없으므로 지연과 재전송의 요청 단위 인과는 확정할 수 없다.

## 완료된 본 측정

실행 루트는 `build/loadtest/runs/20261116-192232-hot-seats`다. 아래 회차는 이 루트의 하위 디렉터리다. 모두 정시 준비 1,000명·지각 0명·예매 표본 1,000건, 정합성·SSE·서버 차분·예열 격리·warmupDelta 검사를 통과했다. 실패 항목은 `toolExit`(Gatling 종료 코드 2)·`bookingP95`·`successP95`다.

| 외부 회차 | 전체 p50 / p95 / p99 (ms) | 성공 p95 (ms) | 900~1,199ms / 1,000ms 이상 |
|---|---|---|---|
| 1 | 253 / 435 / 465 | 383 | 0 / 0 |
| 2 | 284 / 436 / 474 | 365 | 0 / 0 |

전체 본 부하 동안의 호스트 TCP 차분:

| 외부 회차 | ListenOverflows | ListenDrops | TCPTimeouts | TCPSynRetrans | Tcp.RetransSegs |
|---|---|---|---|---|---|
| 1 | 0 | 0 | 14 | 14 | 336 |
| 2 | 0 | 0 | 16 | 16 | 290 |

폭주 시작 직전 연결과 Hikari 획득:

| 외부 회차 | 직전 표본 시차 (ms) | 클라이언트→Gateway | Gateway→core | 직전 Recv-Q (Gateway / core) | Hikari 획득 평균 / p95 (ms) | Hikari timeout |
|---|---|---|---|---|---|---|
| 1 | 48 | 301 | 197 | 0 / 0 | 2.096 / 11.591 | 0 |
| 2 | 37 | 365 | 310 | 0 / 0 | 3.648 / 19.392 | 0 |

폭주 직후 최대 3초 표본 구간에서 두 회차 모두 overflow·drop·timeout·SYN 재전송 증가는 0이었다. Tcp.RetransSegs 증가만 236·264였으며 데이터 재전송과 SYN 재전송을 같은 것으로 해석하지 않는다. 구간 내 Recv-Q 최대는 Gateway/core 각각 158/13, 0/1이었다. 전체 본 부하의 timeout·SYN 증가 14·16건은 폭주 표본 구간 밖의 증가이므로 이를 예매의 약 1초 지연 증거로 쓰지 않는다. 표본은 발생기가 끝날 때 종료하므로 "3초"는 최대 범위다.

100ms 구간별 예매 지연 분포(각 1,000건):

| 외부 회차 | 0~99ms | 100~199ms | 200~299ms | 300~399ms | 400~499ms | 500ms 이상 |
|---|---|---|---|---|---|---|
| 1 | 46 | 348 | 208 | 296 | 102 | 0 |
| 2 | 29 | 266 | 252 | 324 | 129 | 0 |

리슨 backlog 상한은 Gateway·realtime 4096, core·fake-pg 100이었다. `somaxconn=4096`, `tcp_max_syn_backlog=2048`, `tcp_abort_on_overflow=0`이며 변경하지 않았다. 표본 최대 간격 등 상세는 각 `tcp-delta.json`에 있다.

## 미완료 범위와 다음 실험 후보

- 외부 3회차는 예열 도중 중단돼 본 측정값이 없다. 내부 예열 3회는 미실행이다. 재개한다면 내부 계열을 동일 계측으로 측정해 폭주 직전 연결 수와 Hikari 획득을 먼저 비교해야 한다.
- 남는 지연은 Gateway 단계별 타이머·연결 획득·요청 처리 시간과 대조할 후보이며, 포트별 패킷 시각을 수집하면 재전송과 요청 지연의 직접 연결 여부를 확인할 수 있다. 이번에 패킷 캡처나 JFR은 수행하지 않았다.
- backlog·풀·커널 파라미터 변경 실험은 이번 자료만으로 우선 권고할 근거가 부족하다. 향후 내부 계열에서 폭주 시점 overflow가 확인되면 앱 backlog만 바꾸는 대조를 후보로 삼을 수 있다. 호스트 설정 변경은 사용자 승인 후 별도 실험한다.

## 정리와 검증

앱 코드·운영 설정·판정 로직·커널 파라미터는 바꾸지 않았고 커밋하지 않았다. 중단 뒤 18000·18080·18081·18090 리슨 소켓이 없는 것을 확인했다. 기존 fake-pg 컨테이너는 작업 시작부터 정지 상태였고 이번에 컨테이너 stop/start는 하지 않았다. 러너가 올린 PostgreSQL·Redis·관측 스택은 유지했다.

셸 구문 검사와 Python 회귀 검사 14개를 통과했다. 로그는 `/tmp/ticketing-command.FoXVlO7p`·`/tmp/ticketing-command.VKQabgSj`다. 측정 러너의 전체 로그는 `/tmp/ticketing-command.l0KbRsYb`이며, 완료된 회차별 원시 로그·TCP 표본·서버 차분은 위 실행 루트에 보존했다. 기존 초안의 외부 실행 `20261116-161636-hot-seats`는 이번 주 비교에 쓰지 않았다(2회차 warmupDelta 불일치). JFR·순수 콜드는 요청에 따라 생략했고 앱 흐름 변경이 없어 Gradle 전체 테스트·E2E는 추가 실행하지 않았다.

문서 전체 검사와 `git diff --check`도 통과했다. 문서 검사 로그는 `build/loadtest/tcp-diagnosis/docs-check.log`와 `/tmp/ticketing-command.y4q8c3PX`·`/tmp/ticketing-command.Mkqrqxbp`다. 사전에 알려진 `docs/test-performance.md:19` 실패는 이번 검사에서는 재현되지 않았다.
