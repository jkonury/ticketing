# M5-1 기준 측정 결과

2026-11-12에 측정한 M5 기준값이다. M5-2(병목 개선)가 이 문서를 입력으로 쓴다. 측정 방법과 합격 기준의 근거는 [ADR 0024](../adr/0024-observability-and-load-test.md)다.

- 이 문서는 **코드와 설정을 바꾸지 않은 상태의 기준값**이다. 서버를 고치거나 시나리오에 재시도를 넣지 않았다.
- 수치는 실행 디렉터리(`build/loadtest/runs/<타임스탬프>-<시나리오>/<N>/`, git 제외)의 `k6-summary.json`, `prometheus.json`, `integrity.json`, `*.jfr`에서 옮겼다. 산출물은 저장소에 넣지 않는다.
- "중앙값"은 3회 실행의 중앙값이다. 3회 모두 그대로 적었고 좋은 값을 고르려고 다시 돌린 실행은 없다(다시 돌린 경우는 2절 끝에 이유를 적었다).
- 원인은 **확인됨**과 **추정**으로 구분해 적는다.

## 1. 측정 환경

| 항목 | 값 |
|---|---|
| CPU | AMD Ryzen 7 5700G (8코어, 논리 코어 16) |
| 메모리 | 27 GiB |
| OS | Linux 6.8.0-149-generic x86_64 |
| JDK | OpenJDK 25.0.5 (2026-10-20 LTS) |
| k6 | v2.3.0 + xk6-sse v0.2.0 (go1.27.3) |
| 인프라 | PostgreSQL 18.3, Redis 8.6.2 (Docker Compose). Prometheus 3.5.0, Tempo 2.9.0, Grafana 12.1.0 |
| 커밋 | `e0880560901d7252e0d3375000a9e993fd52de69` + 작업 트리 변경(`dirty: yes`). `scripts/loadtest/run.sh`와 `scripts/loadtest/prom-snapshot.sh`(지표 추출 연결)가 커밋되지 않은 상태였고 그 밖에 `.gitignore`·`compose.yaml` 변경이 있었다. 서버 코드·설정에는 변경이 없다 |
| 코어 분리 | k6 `--k6-cpus 0-3`(4코어), 앱 4개(core·realtime·gateway·fake-pg) `--app-cpus 4-15`(12코어를 공유). PostgreSQL·Redis·관측 컨테이너는 코어를 고정하지 않았다 |
| 힙(고정) | core 2g, realtime 1g, gateway 1g, fake-pg 512m |
| 실행 방식 | 앱은 `bootJar`를 `java -jar`로 실행하고 **실행마다 새로 띄운다**(워밍업 없음). Hikari 풀 크기 10(기본값) |
| 추적 샘플링 | `--observability` 실행은 OTLP 내보내기 켬, 비율 0.1. `--jfr` 실행은 추적을 끄고 JFR만 켰다 |
| Prometheus scrape | 5초 간격 |

## 2. 시나리오와 실행 방법

| 구분 | 명령 | VU | 좌석 | 입장 설정 | 결제(fake-pg) |
|---|---|---|---|---|---|
| A 기준 | `run.sh --scenario queue-surge --runs 3 --observability` | 10,000 (1인 1회) | 회차 5,000석(A~E구역 × 25열 × 40석) | 기본값(초당 50명, 1초 간격, 입장 토큰 TTL 10분) | 실패 0%, 웹훅 중복 0%, 웹훅 지연 500ms |
| B 기준 | `run.sh --scenario hot-seats --runs 3 --observability` | 1,000 | 앞줄 100석에 경합 | 입장 배치 1,000명으로 올려 모두 입장시킨 뒤 60초 시점에 동시 시작 | 실패 0% |
| pinning(JFR) | `run.sh --scenario queue-surge --jfr` | 10,000 | 5,000석 | 기본값 | 실패 0% |
| 실패 주입 | `run.sh --scenario hot-seats --failure-inject --observability` | 1,000 | 앞줄 100석 | 배치 1,000명 | 실패 10%, 웹훅 중복 30% |

- 실행 디렉터리: A `20261112-191203-queue-surge`, B `20261112-193238-hot-seats`, JFR `20261112-193756-queue-surge`, 실패 주입 `20261112-194524-hot-seats-fi`.
- 합격 기준은 잠정값이다: 예매 생성 p95 < 300ms, 예상 밖 오류율 < 1%, SSE 연결 성공률 > 99.9%. 중복 판매 0건은 절대 기준이다.
- `unexpected_errors`는 2xx·409 `SEAT_ALREADY_HELD`·429가 아닌 응답과, 입장·좌석 SSE 스트림이 정상 종료(입장 또는 스냅샷 수신)하지 못한 경우를 센다.

**이 문서를 만드는 과정에서 있었던 일(투명성)**

- 이전 세션이 시작한 A 기준 실행(`20261112-185437-queue-surge`)은 3회차 도중 프로세스가 중단되어 완료되지 않았다. 그 실행은 기준값으로 쓰지 않고, A 3회를 처음부터 다시 실행했다. 값이 나빠서 버린 것이 아니다.
- `prom-snapshot.sh`를 처음에는 구간 내 `increase()`로 p95·p99를 구했다. 시나리오 B처럼 짧은 급증이 구간 중간에 처음 생기는 시계열은 첫 표본이 빠져 값이 비거나 작게 나왔다. 앱이 실행마다 새로 떠서 카운터가 0에서 시작하므로 구간 안 마지막 누적값(`last_over_time`)을 쓰도록 스크립트를 고치고, 이미 저장된 Prometheus 시계열에 대해 같은 구간으로 `prometheus.json`을 다시 만들었다. 부하 실행은 다시 하지 않았다. 또 구간 끝을 실행 종료 6초 뒤로 잡아 마지막 scrape를 포함했다.
- 진단용으로 부하 실행 2회를 더 했다(기준값 아님): Redis 키 샘플링(`20261112-194759-queue-surge/1`)과 k6·앱 CPU 샘플링(`20261112-195629-hot-seats/1`). 4절 이후의 "진단" 표기가 이 실행이다.

## 3. 결과

### 3.1 시나리오 A(`queue-surge`, 10,000 VU) k6 지표

| 지표 | 실행 1 | 실행 2 | 실행 3 | 중앙값 |
|---|---|---|---|---|
| 처리량(RPS, k6 http_reqs/s) | 138.7 | 136.4 | 144.9 | 138.7 |
| HTTP 요청 수 | 51009 | 50789 | 51048 | 51009 |
| queue_wait p50 (s) | 278.6 | 280.3 | 263.5 | 278.6 |
| queue_wait p95 (s) | 359.4 | 363.9 | 343.8 | 359.4 |
| confirm_latency p95 (s) | 1.01 | 1.01 | 1.01 | 1.01 |
| booking_create p95 (ms) | 14.3 | 14.3 | 15.2 | 14.3 |
| booking_create 평균 (ms) | 11.1 | 19.9 | 9.4 | 11.1 |
| http_req_duration p95, 전체 (s) | 292.5 | 298.1 | 278.2 | 292.5 |
| http_req_duration p95, expected_response (s) | 15.7 | 14.3 | 14.9 | 14.9 |
| unexpected_errors (%) | 7.65 | 8.32 | 7.56 | 7.65 |
| unexpected_errors 건수 | 2787 | 3050 | 2751 | 2787 |
| sse_connect_success (%) | 99.83 | 99.72 | 99.63 | 99.72 |
| SSE 연결 실패 건수 | 30 | 48 | 65 | 48 |
| http_req_failed 건수(404·500) | 16 | 8 | 18 | 16 |
| booking_conflict | 169 | 251 | 161 | 169 |
| booking_confirmed(확정 예매) | 2036 | 2042 | 2017 | 2036 |
| seats_confirmed | 5000 | 5000 | 5000 | 5000 |
| sold_out_exits | 5175 | 4904 | 5231 | 5175 |
| threshold 예매 생성 p95 < 300ms | 통과 | 통과 | 통과 | 통과 |
| threshold unexpected_errors < 1% | 미달 | 미달 | 미달 | 미달 |
| threshold sse_connect_success > 99.9% | 미달 | 미달 | 미달 | 미달 |

- `http_req_duration` 전체 p95(약 290초)에는 대기열 SSE 스트림 유지 시간이 섞여 있어 API 지연이 아니다. API 지연은 `expected_response`와 이름별 값(3.3절)을 본다.
- `confirm_latency` 약 1.0초는 fake-pg 웹훅 지연 500ms와 확정 폴링 간격 1초의 합에 가깝다. 서버가 느려서 나온 값이 아니라 시나리오·fake-pg 설정이 만든 하한이다.
- http_req_failed의 구성: 실행 1은 입장(`queue_enter`) 404 16건, 실행 2는 입장 404 8건, 실행 3은 Gateway 500 18건(`POST /api/v1/sse-tickets`, 5절 후보 5).

### 3.2 시나리오 B(`hot-seats`, 1,000 VU) k6 지표

| 지표 | 실행 1 | 실행 2 | 실행 3 | 중앙값 |
|---|---|---|---|---|
| 처리량(RPS, k6 http_reqs/s) | 54.3 | 57.0 | 32.1 | 54.3 |
| HTTP 요청 수 | 3453 | 3614 | 2011 | 3453 |
| queue_wait p50 (s) | 6.2 | 6.9 | 6.1 | 6.2 |
| queue_wait p95 (s) | 6.3 | 7.0 | 6.2 | 6.3 |
| confirm_latency p95 (s) | 2.09 | 2.10 | 2.12 | 2.10 |
| booking_create p95 (ms) | 1961.7 | 1717.6 | 512.7 | 1717.6 |
| booking_create 평균 (ms) | 1511.3 | 1287.7 | 442.3 | 1287.7 |
| http_req_duration p95, 전체 (s) | 3.7 | 4.3 | 3.7 | 3.7 |
| unexpected_errors (%) | 9.55 | 6.91 | 45.82 | 9.55 |
| unexpected_errors 건수 | 259 | 194 | 855 | 259 |
| sse_connect_success (%) | 98.71 | 97.58 | 37.08 | 97.58 |
| SSE 연결 실패 건수 | 10 | 20 | 246 | 20 |
| 입장(`queue_enter`) 404 건수 | 224 | 174 | 609 | 224 |
| booking_conflict | 676 | 744 | 107 | 676 |
| booking_confirmed(확정 예매) | 65 | 62 | 38 | 62 |
| seats_confirmed | 94 | 95 | 69 | 94 |
| threshold 예매 생성 p95 < 300ms | 미달 | 미달 | 미달 | 미달 |
| threshold unexpected_errors < 1% | 미달 | 미달 | 미달 | 미달 |
| threshold sse_connect_success > 99.9% | 미달 | 미달 | 미달 | 미달 |

- 실행 3은 입장 직후 404 경합(4.3절)이 가장 심해(1,000명 중 609명이 입장 요청에서 404, 246건은 순번 스트림 연결 404) 예매까지 간 사용자가 391명뿐이다. 좌석 경합 규모가 실행마다 달라 예매 생성 지연도 다르다(512.7ms ~ 1961.7ms). 이상치로 빼지 않고 중앙값에 포함했다.
- 시나리오 B는 60초 지연 뒤 모든 VU가 동시에 시작하는 구조라 실제 부하 구간은 수 초이고, 앱은 워밍업 없이 이 급증을 받는다(5절 후보 2).
- 진단용으로 다시 돌린 `20261112-195629-hot-seats/1`도 같은 모양이었다(http_req_failed 34.5%, 예매 생성 p95 472.9ms). 기준값에는 넣지 않았다.

### 3.3 이름별 지연: 서버 측과 k6 측

k6 `--summary-export`는 예매 생성만 이름별로 내보낸다. 나머지는 k6가 Prometheus로 보낸 1초 단위 p95·p99 시계열의 중앙값을 썼다(구간 전체 분위수가 아니라 "1초 구간 분위수의 중앙값"이다). 서버 측은 앱의 `http.server.requests` 구간 전체 분포다. 3회 중앙값이며 단위는 ms다.

| 시나리오 | 요청 | k6 p95 | k6 p99 | 서버 p95 | 서버 p99 | 서버 측정 위치 |
|---|---|---|---|---|---|---|
| A | `queue_enter` | 10,665 | 10,760 | 8,554 | 9,557 | realtime `/queues/{id}/entries` |
| A | `sse_ticket` | 3,378 | 3,631 | 62 | 89 | core `/sse-tickets` |
| A | `booking_create` | 33.6 | 160.0 | 9 | 29 | core `/bookings` |
| A | `booking_pay` | 20.6 | 60.8 | 6 | 19 | core `/bookings/{id}/payments` |
| A | `booking_detail` | 10.3 | 73.8 | 4 | 9 | core `/bookings/{id}` |
| B | `queue_enter` | 2,844 | 2,854 | 2,457 | 2,496 | realtime `/queues/{id}/entries` |
| B | `sse_ticket` | 646 | 696 | 32 | 68 | core `/sse-tickets` |
| B | `booking_create` | 1,523 | 1,663 | 1,401 | 1,687 | core `/bookings` |
| B | `booking_pay` | 538 | 756 | 528 | 750 | core `/bookings/{id}/payments` |
| B | `booking_detail` | 103 | 150 | 99 | 146 | core `/bookings/{id}` |

- 대기열 SSE 스트림(`/streams/queues/...`, `/streams/schedules/.../seats`)의 서버 p95는 `30000ms`로 찍힌다. 히스토그램 최대 버킷이라 "30초 이상"이라는 뜻이고 지연 값이 아니다. 스트림 연결 자체는 지연 지표로 보지 않는다.
- Gateway는 `http.server.requests`의 `uri` 태그가 모두 `UNKNOWN`이라 경로별로 나눌 수 없다. Gateway와 네트워크 구간의 지연은 위 표의 k6 측과 서버 측 차이로만 추정한다.
- 차이 해석(추정): A에서 `booking_*`는 서버·k6가 같은 자릿수(수십 ms 이내)라 Gateway·네트워크 구간이 작다. B의 `booking_create`·`booking_pay`·`booking_detail`은 두 값이 거의 같아 지연의 대부분이 core 안에서 생긴다. 반면 `sse_ticket`은 A에서 서버 62ms인데 k6는 3.4초, B에서 32ms 대 646ms로 서버 밖에서 늘어난다. 이 구간이 Gateway인지 k6인지는 이번 측정으로 구분하지 못했다(5절 후보 5).

### 3.4 서버 측 지표(`prometheus.json`)

URI별 p95·p99(ms, 실행 1/2/3과 중앙값), 요청 수는 앱 기동 이후 누적이라 시드·준비 호출을 일부 포함한다.

시나리오 A

| 앱 | URI | 요청 수(1/2/3) | p95 ms(1/2/3, 중앙) | p99 ms(1/2/3, 중앙) | RPS 최대(중앙) |
|---|---|---|---|---|---|
| core | `/api/v1/bookings` | 2205/2293/2178 | 10/9/9 (9) | 29/129/16 (29) | 16.2/17.5/17.8 (18) |
| core | `/api/v1/bookings/{id}` | 2040/2076/2017 | 4/4/4 (4) | 9/96/7 (9) | 13.6/12.8/14.4 (14) |
| core | `/api/v1/bookings/{id}/payments` | 2036/2042/2017 | 7/6/6 (6) | 19/86/12 (19) | 13.7/12.8/15.1 (14) |
| core | `/api/v1/payments/webhooks/fake-pg` | 2036/2042/2017 | 6/6/6 (6) | 13/168/11 (13) | 14.1/12.8/15.0 (14) |
| core | `/api/v1/sse-tickets` | 17364/17189/17409 | 62/62/52 (62) | 89/104/86 (89) | 394.8/435.1/354.5 (395) |
| fake-pg | `/v1/payments` | 2036/2042/2017 | 3/3/3 (3) | 9/436/5 (9) | 13.8/12.8/15.2 (14) |
| realtime | `/api/v1/queues/{scheduleId}/entries` | 10000/10000/10000 | 8554/8495/8727 (8554) | 9557/8571/9763 (9557) | 388.3/432.8/382.8 (388) |
| realtime | `/api/v1/streams/schedules/{scheduleId}/seats` | 7380/7197/7409 | 1278/1191/983 (1191) | 2233/1969/1452 (1969) | 46.0/45.4/46.6 (46) |
| gateway | 전체(`UNKNOWN`) | 50963/50733/51001 | 30000(최대 버킷) | 30000(최대 버킷) | 736.4/832.3/699.5 (736) |

시나리오 B(1,000 VU, 부하 구간이 짧아 RPS 최대는 5초 scrape로 포착하지 못해 싣지 않는다)

| 앱 | URI | 요청 수(1/2/3) | p95 ms(1/2/3, 중앙) | p99 ms(1/2/3, 중앙) |
|---|---|---|---|---|
| core | `/api/v1/bookings` | 741/806/145 | 1664/1401/483 (1401) | 1853/1687/526 (1687) |
| core | `/api/v1/bookings/{id}` | 95/94/46 | 99/93/128 (99) | 127/146/169 (146) |
| core | `/api/v1/bookings/{id}/payments` | 65/62/38 | 598/528/135 (528) | 926/750/152 (750) |
| core | `/api/v1/payments/webhooks/fake-pg` | 65/62/38 | 169/201/204 (201) | 254/302/324 (302) |
| core | `/api/v1/sse-tickets` | 776/826/391 | 32/84/31 (32) | 52/111/68 (68) |
| fake-pg | `/v1/payments` | 65/62/38 | 936/1018/353 (936) | 975/1063/357 (975) |
| realtime | `/api/v1/queues/{scheduleId}/entries` | 1000/1000/1000 | 2144/2476/2457 (2457) | 2420/2500/2496 (2496) |

앱별 자원(실행 1/2/3과 중앙값). CPU는 JVM이 보고한 `process.cpu.usage`(0~1을 %로 환산, 가용 코어 대비 비율)다.

| 시나리오 | 앱 | CPU 최대 % | CPU 평균 % | 힙 사용 최대 MB | GC 일시 정지 합계 s |
|---|---|---|---|---|---|
| A | core | 20.6/19.4/15.7 (19.4) | 2.1/2.0/2.1 (2.1) | 1376/1374/1373 (1374) | 0.18/0.14/0.15 (0.15) |
| A | realtime | 32.0/32.0/29.6 (32.0) | 8.4/8.5/8.5 (8.5) | 869/840/853 (853) | 4.57/4.80/4.20 (4.57) |
| A | gateway | 38.2/27.9/34.3 (34.3) | 4.2/4.1/4.4 (4.2) | 954/968/957 (957) | 1.60/2.04/1.65 (1.65) |
| A | fake-pg | 3.2/4.5/2.4 (3.2) | 0.3/0.3/0.3 (0.3) | 318/327/319 (319) | 0.04/0.05/0.03 (0.04) |
| B | core | 23.6/25.0/12.5 (23.6) | 2.8/4.2/2.6 (2.8) | 1271/1230/1268 (1268) | 0.07/0.06/0.04 (0.06) |
| B | realtime | 18.1/21.6/20.5 (20.5) | 2.1/2.4/1.9 (2.1) | 544/563/462 (544) | 0.00 |
| B | gateway | 19.6/29.0/14.6 (19.6) | 2.9/3.2/2.1 (2.9) | 596/484/477 (484) | 0.00/0.04/0.00 |

| 시나리오 | Redis 명령/s 최대 (1/2/3) | PostgreSQL 연결 최대 (1/2/3) |
|---|---|---|
| A | 19,898 / 20,157 / 20,605 | 12 / 12 / 13 |
| B | 750 / 980 / 799 | 12 / 12 / 12 |

### 3.5 k6 VU 수별 메모리(진단용 측정)

부하 생성기 메모리가 1만 VU에서 문제가 되는지 보려고 `queue-surge`를 VU 수만 바꿔 1회씩 실행하고, k6 프로세스의 최대 RSS를 0.5초 간격으로 샘플링했다. 기준 측정(3회 중앙값)이 아니라 진단용 1회 측정이며, 앱 구성과 `taskset` 코어 분할은 기준 측정과 같다.

| VU | 최대 RSS | VU당 |
|---|---|---|
| 500 | 0.55 GiB | 1.16 MiB |
| 1,000 | 1.04 GiB | 1.07 MiB |
| 2,000 | 1.83 GiB | 0.94 MiB |
| 5,000 | 3.86 GiB | 0.81 MiB |

- 2,000에서 5,000 VU 구간의 증가 기울기는 VU당 약 0.7 MiB다. 이 기울기로 1만 VU를 외삽하면 약 7.2 GiB다. 선형 가정이라 실제 값은 다를 수 있고, 1만 VU 실측은 하지 않았다.
- 좌석 스냅샷을 VU마다 `JSON.parse`하던 초기 구현은 1,000 VU에서 3.45 GiB였다. 현재는 `readSeatSnapshot`이 정규식으로 AVAILABLE 좌석 ID만 뽑는다. 이 정규식은 realtime `SeatSnapshot`의 필드 순서(`seatId`, `status`, `holdExpiresAt`)에 의존한다.
- 호스트 메모리는 27 GiB다. 1만 VU 외삽값은 그 안에 들어가므로 k6 메모리는 현재 병목이 아니다.

## 4. 분석

### 4.1 정합성

모든 실행(A 3회, B 3회, JFR 1회, 실패 주입 1회, 진단 2회)에서 `verify-integrity.sh`가 통과했다. **중복 판매는 0건**이다.

| 실행 | `soldSeatDuplicates` | `confirmedSeatDuplicates` | 확정 좌석 수 | `sold_seat` 수 | Redis SOLD 수(불일치) | 상태별 예매 수 |
|---|---|---|---|---|---|---|
| A-1 | 0 | 0 | 5000 | 5000 | 5000 (0) | CONFIRMED 2036 |
| A-2 | 0 | 0 | 5000 | 5000 | 5000 (0) | CONFIRMED 2042 |
| A-3 | 0 | 0 | 5000 | 5000 | 5000 (0) | CONFIRMED 2017 |
| B-1 | 0 | 0 | 94 | 94 | 94 (0) | CONFIRMED 65 |
| B-2 | 0 | 0 | 95 | 95 | 95 (0) | CONFIRMED 62 |
| B-3 | 0 | 0 | 69 | 69 | 69 (0) | CONFIRMED 38 |
| JFR(A 설정) | 0 | 0 | 5000 | 5000 | 5000 (0) | CONFIRMED 2012 |
| 실패 주입(B 설정) | 0 | 0 | 67 | 67 | 67 (0) | CONFIRMED 42, CANCELLED 9 |

- k6의 `seats_confirmed`·`booking_confirmed`는 위 DB 값과 모두 일치한다.
- **실패 주입(실패 10%, 웹훅 중복 30%)**: 결제 요청 51건 중 확정 42건, 취소 9건이다. 취소 9건은 `booking_not_confirmed` 9건과 일치한다. 코어가 받은 웹훅은 75건(`200` 75건, 오류 없음)이고 결제 요청은 51건이므로 24건이 중복 전달이다. 중복 웹훅이 있어도 확정 예매·확정 좌석 수는 중복 없이 맞았다(결제·웹훅 멱등 키가 이 부하에서 동작함).
- 확인하지 못한 것: `SEAT_ALREADY_SOLD`와 환불 보상 경로는 이 부하에서 발생하지 않았다(코어·fake-pg 로그에 해당 문자열 없음). 좌석 선점(Redis hold) 단계에서 경합이 모두 `SEAT_ALREADY_HELD`로 걸러졌기 때문으로 보이며(추정), 보상 경로의 부하 검증은 남은 과제다. `verify-integrity.sh`에도 환불 정합성 검사는 없다.

### 4.2 Virtual Thread 점검

**JFR `jdk.VirtualThreadPinned`** (`20261112-193756-queue-surge/1`, `--jfr`, 임계 0ms, 444초 기록, `jfr print --events jdk.VirtualThreadPinned --stack-depth 25`)

| 앱 | VirtualThreadPinned 건수 | 최장 | 합계 | 차단 유형 | 상위 호출 위치(첫 비JDK 프레임) |
|---|---|---|---|---|---|
| core | 755 | 59.7ms(`Object.wait`, `jdk.proxy2.$Proxy260` 클래스 초기화 대기) | 약 0.84초 | `Contended monitor enter` 638, `Object.wait` 117 | `JarUrlClassLoader.loadClass` 269, jOOQ `TableImpl.getRecordConstructor` 33, `SimpleClientHttpRequestFactory.openConnection` 27 |
| fake-pg | 1,371 | 18.7ms | 약 0.36초 | `Contended monitor enter` 1,134, `Object.wait` 237 | `WebhookSender.post` 452, `JarUrlClassLoader.loadClass` 367 |
| realtime | 0 | - | - | - | - |
| gateway | 0 | - | - | - | - |

- 이벤트는 모두 JVM 기동 직후 구간에 몰려 있다. core 755건 중 736건이 첫 예매·결제가 처리된 19:39:05~06의 2초 안에 있고(19건은 기동 중), fake-pg 1,371건은 모두 첫 결제가 들어온 19:39:06 한 초 안이다. 이후 약 6분 동안 새 이벤트가 없다. 고정 사유는 클래스 로딩·클래스 초기화 대기(`ClassLoader.loadClass`, `Waited for initialization of ...`, `Freeze or preempt failed`)로, JEP 491 이후 남는 JVM 내부 사유다. **정상 상태(steady state)의 pinning은 확인되지 않았다.**
- `jdk.VirtualThreadSubmitFailed`는 4개 앱 모두 0건이다. Prometheus `jvm.threads.virtual.*`도 일치한다: A 3회 core `pinnedEvents` 1/1/0(최장 75.8ms, 20.2ms), B 3회 core 1/21/4(최장 70.3/43.7/21.4ms), fake-pg 8/17/0, 모든 실행에서 `submitFailed` 0이다. Prometheus 쪽 건수가 JFR(임계 0ms)보다 훨씬 적은 것은 Micrometer가 기본 임계 이상만 세기 때문으로 추정한다.
- 결론: Virtual Thread pinning은 이번 측정에서 병목이 아니다. 다만 위 이벤트는 "기동 직후 첫 요청"에 몰리므로 5절 후보 2의 워밍업 문제와 함께 본다.

**Hikari** (core, 풀 크기 10)

| 시나리오 | 실행 | `pending` 최대 | `active` 최대 | 연결 획득 횟수 | 획득 평균 | 획득 최대 | 타임아웃 |
|---|---|---|---|---|---|---|---|
| A | 1 | 0 | 2 | 39,201 | 0.01ms | 43.6ms | 0 |
| A | 2 | 0 | 2 | 39,585 | 0.78ms | 363ms | 0 |
| A | 3 | 0 | 2 | 38,807 | 0.004ms | 2.5ms | 0 |
| B | 1 | 0 | 1 | 3,298 | 164.0ms | 1,642ms | 0 |
| B | 2 | 0 | 0 | 3,442 | 159.8ms | 1,349ms | 0 |
| B | 3 | **52** | **10** | 753 | 36.2ms | 205ms | 0 |
| 실패 주입 | 1 | 0 | 0 | 1,851 | 80.9ms | 669ms | 0 |

- A에서는 `pending`이 0이고 `active`가 2를 넘지 않아 풀이 병목이 아니다.
- B에서는 `pending`·`active` 게이지가 5초 간격 표본이라 급증 구간을 놓친다. 실행 3은 우연히 급증 중에 표본이 잡혀 `pending` 52, `active` 10(풀 크기 10에 도달)이 보였다. 실행 1·2는 게이지 값이 0이지만 연결 획득 평균이 약 160ms, 최대 1.3~1.6초로 **획득 대기가 실제로 있었다**(대기가 없었다면 A처럼 평균이 0.01ms 수준이다). 풀 크기 10에서 B 급증 시 연결 대기가 발생했다는 것은 확인됨이다.
- 획득 시간 p95는 구하지 못했다. Hikari 획득 타이머가 버킷 히스토그램을 내보내지 않아 합계·건수·최대값(평균·최대)만 남는다. M5-2에서 필요하면 히스토그램 노출을 추가한다.

### 4.3 입장 직후 404 경합(사용자 결정에 따라 그대로 측정)

step 6에서 확인된 realtime 대기열의 입장 직후 `status`/`enter` 404를 **고치지 않고 오류로 집계**했다. 결함 후보로 기록한다.

| 시나리오 | 실행 | 입장(`queue_enter`) 404 | 순번 스트림(`sse_queue`) 연결 404 | 비율(입장 404 / 사용자) |
|---|---|---|---|---|
| A | 1 | 16 | 30 | 0.2% |
| A | 2 | 8 | 48 | 0.1% |
| A | 3 | 0 | 47 | 0% |
| B | 1 | 224 | 10 | 22.4% |
| B | 2 | 174 | 20 | 17.4% |
| B | 3 | 609 | 246 | 60.9% |
| 실패 주입 | 1 | 404 | 271 | 40.4% |

(건수는 `k6-summary.json`의 `http_req_failed`·`sse_connect_success`와 Prometheus에 보낸 `k6_http_reqs_total{name,status}`에서 센 값이다. A-3의 `http_req_failed` 18건은 입장 404가 아니라 Gateway 500이다.)

- 확인됨(관측): 입장 요청(`POST /api/v1/queues/{scheduleId}/entries`)과 순번 스트림의 첫 상태 조회가 `404 QUEUE_ENTRY_NOT_FOUND`를 받는다. 입장 배치를 1,000명으로 올린 B에서 가장 많다.
- 추정(코드 읽기, 로그·추적으로 순서를 확정하지 않음): `QueueService.enter`는 `waiting`에 추가한 뒤 `seen`을 기록하고 순번을 조회한다. 그 사이에 admit 스케줄러(`admit.lua`)가 돌면 (1) `seen`이 없는 항목을 만료로 보고 대기열에서 제거하거나 (2) 이미 입장시켜 대기열에서 뺀다. 그러면 마지막 순번 조회가 404가 된다. 순번 스트림의 첫 조회도 입장·제거 직후면 같은 결과가 난다.
- 영향: 404를 받은 사용자는 시나리오에서 이탈하므로 B의 실제 경합 인원이 1,000명이 아니라 391~826명이다. 즉 B의 지연 수치는 설계된 경합보다 약한 부하에서 나온 값이다.
- M5-2에서 이 경합을 고치기 전에 재현 테스트로 순서를 먼저 확정한다.

## 5. 병목·결함 후보와 M5-2 제안

영향 순서. 증상은 지표 값이고 원인은 확인됨/추정을 구분한다.

### 후보 1. 대기열 이탈 27%와 입장 속도 저하(시나리오 A)

- **증상**: A에서 `unexpected_errors` 7.56~8.32%(합격 < 1%의 약 8배). 10,000명 중 약 2,750명이 입장하지 못하고 대기열 스트림이 끝난다. 입장은 7,200명 안팎이고 평균 입장 속도는 약 21명/초(진단 실행: 347초 동안 7,231명)로 설정값(초당 50명)의 절반 이하다. 매진까지 걸린 시간은 약 4분(240초)이고 `queue_wait` p95는 약 6분이다.
- **원인**:
  - 확인됨(진단, `20261112-194759-queue-surge/1`): 10초 간격 Redis 샘플링에서 `queue:waiting`의 감소량이 `queue:admitted` 키 증가량보다 일관되게 컸다. 예: 한 구간에서 waiting 9,000 → 8,500(−500)일 때 admitted 560 → 828(+268). 마지막 표본은 admitted 7,231, waiting 150이다. `admit.lua`는 `queue:seen` 점수가 `presenceGrace`(30초)보다 오래된 항목을 입장시키지 않고 대기열에서 제거하므로, 차이만큼의 사용자가 **입장이 아니라 존재 만료로 제거**된 것이다(코드 `realtime/src/main/resources/redis/queue/admit.lua`).
  - 추정: 제거된 항목의 `seen`이 없거나 30초 넘게 갱신되지 않은 이유. 후보 (a) `enter`가 `waiting` 추가 뒤에 `seen`을 기록하므로(4.3절), 입장 요청 지연이 8.5초에 달하는 급증 구간에서 그 사이에 스케줄러가 `seen` 없는 항목을 제거한다. (b) 스트림 하트비트(15초 간격 `status`)가 realtime의 부하로 지연된다. 어느 쪽이 주된 원인인지는 구분하지 못했다. 입장 속도가 설정값보다 낮은 이유(스케줄러 한 번에 앞쪽 후보 200명을 보고 만료 항목을 처리하느라 입장 수가 줄어듦)도 후보이나 확인하지 못했다.
- **근거**: `20261112-191203-queue-surge/{1,2,3}/k6-summary.json`(`unexpected_errors`, `sse_connect_success`, `queue_wait`), 진단 샘플링 `20261112-194759-queue-surge/1`의 Redis 샘플.
- **M5-2 아이디어**: (1) 입장·제거 건수 지표(`admitted`, `evicted`, `removed`)를 먼저 추가해 확정. (2) 위치 폴링은 `updateSeen`을 갱신하게 하거나 `presenceGrace`·하트비트 간격을 조정. (3) 만료 제거 시 클라이언트에 `removed`가 아니라 재진입 안내를 주는지 검토. 기대 효과: `unexpected_errors`를 1% 아래로, 입장 속도를 설정값에 근접.

### 후보 2. 시나리오 B의 예매 생성 지연과 Hikari 풀 대기

- **증상**: B 예매 생성 p95 512.7~1,961.7ms(중앙값 1,717.6ms, 합격 < 300ms), 서버 측 core `/bookings` p95 1.4초(중앙값). 결제 요청(`/bookings/{id}/payments`) p95 528ms, fake-pg `/v1/payments` p95 936ms. A(같은 API, 부하 분산)는 예매 생성 p95 14.3ms.
- **원인**:
  - 확인됨: 지연이 core 안에서 생긴다(3.3절, 서버 p95와 k6 p95가 같은 자릿수). Hikari 풀 크기 10에서 연결 획득 평균 160ms, 최대 1.6초이고(실행 3은 `pending` 52, `active` 10), 연결 획득은 약 3,300~3,400회로 예매 요청 약 740~800건당 4회가 넘는다.
  - 추정: 예매 한 건이 트랜잭션을 여러 번 열어 풀을 오래·여러 번 점유하는 구조와 풀 크기 10이 겹친 것. 급증을 워밍업 없이 받은 앱(JIT·클래스 로딩)의 영향도 섞여 있을 수 있다. fake-pg `/v1/payments` p95 936ms는 fake-pg CPU 4%인데도 느려 외부 대기가 아니라 기동 직후 첫 요청 지연(클래스 로딩·JIT)일 가능성이 있다(4.2절 pinning 이벤트가 첫 결제에 몰린 것과 일치).
- **근거**: `20261112-193238-hot-seats/{1,2,3}/prometheus.json`(`hikari`, `http`), `k6-summary.json`.
- **M5-2 아이디어**: (1) 워밍업 구간을 시나리오에 넣어 콜드 스타트와 실제 한계를 분리해 다시 측정. (2) 예매 생성 한 요청의 트랜잭션·연결 획득 횟수를 줄임. (3) 풀 크기를 근거와 함께 조정(DB 연결 수 한도 확인 필요). 기대 효과: 예매 생성 p95를 300ms 아래로.

### 후보 3. 입장 직후 404 경합(결함 후보)

- **증상**: 4.3절. B에서 입장 요청 404가 사용자의 17~61%, A에서 최대 0.2%. B의 실제 경합 인원이 391~826명으로 줄어 측정 자체가 약해진다. `unexpected_errors`와 `sse_connect_success` 미달에도 기여한다.
- **원인**: 확인됨 — 서버가 `404 QUEUE_ENTRY_NOT_FOUND`를 반환한다(관측). 추정 — `enter`의 `waiting` 추가, `seen` 기록, 순번 조회가 원자적이지 않아 그 사이에 admit 스케줄러가 항목을 제거하거나 입장시킨다(4.3절).
- **근거**: `k6_http_reqs_total{name="queue_enter",status="404"}`(Prometheus), `20261112-193238-hot-seats/{1,2,3}`.
- **M5-2 아이디어**: 입장 직후 조회는 입장 토큰 키를 먼저 보고, 입장 요청·순번 스트림이 대기열 이탈을 입장 완료와 구분하게 한다. 재현 테스트로 순서를 확정한 뒤 수정. 기대 효과: B의 경합 인원 확보, 오류율 개선. 사용자 결정에 따라 이번 측정에서는 고치지 않았다.

### 후보 4. realtime 입장 요청 지연과 GC

- **증상**: A에서 realtime `/queues/{id}/entries` p95 8.5초(p99 9.6초, k6 측 약 10.7초). 같은 요청이 B(1,000명)에서는 2.5초. realtime GC 일시 정지 합계가 A에서 4.2~4.8초, 힙 사용 최대 840~869MB(최대 1GB). JFR 실행에서 realtime은 GC 490회, 합계 약 6.1초, 최장 168ms, Full GC 1회.
- **원인**:
  - 확인됨: 지연은 realtime 서버 측에서 이미 8.5초다(Gateway·k6 쪽 지연이 아니다).
  - 추정: 1만 개 동시 연결의 입장 요청 급증과 순번 폴링(1초 간격, Redis 명령 최대 약 2만/초)이 realtime의 Redis 접근 경로(단일 Lettuce 연결 사용으로 보이나 설정은 확인하지 않음)와 힙 압박에 겹친 결과. CPU는 최대 32%로 한계가 아니다.
- **근거**: `20261112-191203-queue-surge/{1,2,3}/prometheus.json`, `20261112-193756-queue-surge/1/realtime.jfr`.
- **M5-2 아이디어**: realtime 힙을 키워 GC 영향을 분리 측정, 순번 폴링 주기·Redis 연결 설정 점검, 입장 요청 경로의 Redis 왕복 수 축소. 기대 효과: 입장 요청 p95 감소(`queue_wait`와 대기열 이탈의 일부 원인 제거 가능).

### 후보 5. 서버 밖 지연(`sse_ticket`)과 Gateway 오류, 측정 공백

- **증상**: A에서 `sse_ticket` k6 p95 약 3.4초인데 core 서버 p95는 62ms. A-3에서 Gateway가 `POST /api/v1/sse-tickets`에 500 18건(`PrematureCloseException` 15, `IOException` 3). A에서 Gateway 힙 최대 957MB(최대 1GB), GC 1.6~2.0초, JFR 실행에서 Gateway Full GC 2회.
- **원인**:
  - 확인됨: Gateway→core 구간에서 연결이 중간에 끊긴 오류가 18건 있었다(Gateway 지표·로그). 서버 p95와 k6 p95 차이도 확인됨.
  - 추정: 차이의 위치는 Gateway(힙 압박, 연결 풀) 또는 부하 발생기(k6 4코어에서 1만 VU와 SSE 이벤트 초당 약 3,000건 처리)다. 진단 실행(B 규모 1,000 VU)에서 k6는 최대 131%(4코어 중 약 1.3코어)로 포화하지 않았으나, 1만 VU인 A에서는 k6 CPU를 측정하지 못했다. 500의 원인도 Gateway 아웃바운드 커넥션 재사용 경합으로 추정할 뿐 확인하지 못했다.
- **근거**: `20261112-191203-queue-surge/3/gateway.log`(18건), `prometheus.json`, `20261112-195629-hot-seats/1`(k6 CPU 샘플).
- **M5-2 아이디어**: (1) 측정 공백 먼저 해소: Gateway 경로(route id)별 지표, 입장·퇴장 건수 지표, k6 호스트 CPU 기록, Hikari 히스토그램. (2) Gateway→core 연결 풀·idle 타임아웃과 Gateway 힙 점검. 기대 효과: 다음 측정에서 지연 위치를 Gateway·k6·서버로 가를 수 있다.

### 병목이 아닌 것으로 확인한 항목

- Virtual Thread pinning(4.2절): 정상 상태 pinning 없음.
- A의 Hikari 풀(`pending` 0, `active` ≤ 2).
- A의 core·fake-pg·PostgreSQL: core CPU 평균 2%, 예매 생성 서버 p95 9ms, PostgreSQL 연결 12~13개.
- 예매 생성의 합격 기준은 A에서 이미 통과한다(14.3ms). 미달 항목은 `unexpected_errors`와 `sse_connect_success`(A), 세 항목 모두(B)다.

## 6. M5-2에 넘기는 제안 요약

1. 개선 전에 측정 공백부터 채운다(입장·제거·퇴장 건수, Gateway route 지표, Hikari 히스토그램, k6 CPU). 후보 1·4·5의 원인 확정이 여기에 달려 있다.
2. 시나리오 B에 워밍업 구간을 추가해 콜드 스타트와 실제 한계를 분리하고, 입장 404 경합(후보 3)을 고친 뒤 재측정한다.
3. 후보 1(대기열 이탈), 후보 2(Hikari·예매 트랜잭션) 순으로 개선하고, 개선마다 같은 명령으로 3회 중앙값을 다시 낸다.
4. 보상 경로(`SEAT_ALREADY_SOLD`, 환불)는 별도 부하 시나리오로 검증한다(4.1절).

## 7. 한계

- **단일 머신**: k6, 앱 4개, PostgreSQL, Redis, 관측 스택이 한 호스트에서 돈다. 코어 고정은 k6와 앱 사이만 나눴고 PostgreSQL·Redis·Prometheus 등은 고정하지 않았다. 네트워크는 루프백이라 실제 네트워크 지연이 없다.
- **k6와 앱의 동일 호스트 실행**: 부하 발생기의 지연이 측정에 섞일 수 있다(후보 5). A 규모(1만 VU)에서 k6 CPU는 측정하지 못했다. 메모리는 3.5절에 있다.
- **추적 샘플링 0.1**: `--observability` 실행에는 OTLP 내보내기(비율 0.1) 부담이 들어 있다. JFR 실행은 추적을 껐으나 JFR `profile` 설정 부담이 있다. 두 부담을 분리한 비교 실행은 하지 않았다.
- **게이지 표본**: `pending`·`active` 최대값과 B의 RPS 최대는 5초 scrape 표본이라 짧은 급증을 놓친다. 이 문서의 B `pending`은 하한에 가깝다.
- **콜드 스타트**: 앱을 실행마다 새로 띄우고 워밍업이 없어 JIT·클래스 로딩이 B와 실패 주입 실행의 지연에 섞여 있다(5절 후보 2, 추정).
- **k6 이름별 분위수**: `--summary-export`가 이름별 값을 주지 않아 3.3절 k6 값은 1초 구간 분위수의 중앙값이다. 구간 전체 분위수와 다르다.
- **1회 JFR·1회 실패 주입**: JFR 분석과 실패 주입은 각 1회라 중앙값이 없다. 실패 주입은 표본이 작다(결제 51건).
- 요청 수 단위 서버 지표는 앱 기동 이후 누적이라 시드·준비 호출이 일부 섞여 있다.
