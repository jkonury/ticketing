# M5 내부 예열의 실제 예매 구간 트래픽 혼합

> 허브: [M5-2 개선 및 측정 인계](../m5-improvements.md) · 이전: [예열의 실제 예매 핸들러 경유](17-warmup-through-real-handler.md) · 결정: [ADR 0032](../../adr/0032-warmup-traffic-mix.md)

## 결론

예열 요청을 실제 예매 구간 구성(예매 생성 1~4장, 결제 요청, 상세 조회, 웹훅 처리, PG 송신)으로 한 회차 안에서 섞었다. **core 예매 구간 탈최적화는 203건에서 52건으로 줄었고** Hibernate UPDATE·INSERT 계열은 0건이 됐다. 그러나 **JFR OFF 3회 전체 예매 p95는 760·772·750ms로 300ms 목표에 모두 미달**이다. 직전 3회(709·1,458·816ms)와 비교하면 중앙값은 비슷하고(816 → 760ms) 변동은 줄었으나, 탈최적화 감소가 p95 개선으로 이어지지 않았다. 따라서 "core 탈최적화가 p95 지연의 주원인"이라는 가설은 이번 측정으로 지지되지 않는다. 순수 콜드 회귀는 생략했다.

> 2026-11-16 900ms 잠정 기준 재판정([16](16-startup-warmup-burst-volume.md#목표-전환)): 전체 p95 760·772·750ms, 성공 p95 677·707·707ms로 3회 모두 900ms 미만이라 충족한다. 300ms 장기 목표는 미달이다.

## 탈최적화 귀속 분석 (변경 전, `20261116-140027-hot-seats/1/core.jfr`)

예매 구간 203건을 스레드·스택 프레임군으로 나눴다(스택은 상위 64프레임에서 잘려 125건이 진입점까지 이어지지 않는다. 스크립트는 scratchpad의 `attr*.py`).

| 구분 | 건수 | 내용 |
|---|---|---|
| `task-*` 가상 스레드 | 77 (38%) | PG 송신. `DefaultRestClient` exchange 25, 트레이싱 `createSenderSpan` 19, Micrometer 관측, Jackson 본문 쓰기. Hibernate 0 |
| `tomcat-*` 요청 스레드 | 124 (61%) | Hibernate 쓰기, Jackson 역직렬화·캐시, 이벤트 멀티캐스터(`PaymentRequested` 발행·`TransactionalEventListeners.filter`) |
| 기타 | 2 | 관측 내보내기 |

Hibernate 프레임군은 `UpdateCoordinatorStandard` 17건, `InsertCoordinatorStandard` 6건이었다. 기존 예열은 예매 승자를 IDENTITY insert 후 롤백하므로 insert만 예열되고, **결제 요청의 예매 UPDATE·결제 insert/UPDATE·웹훅 승인 UPDATE는 한 번도 실행되지 않았다.** 이 가설("예열은 롤백, 실제는 커밋·갱신")은 UPDATE 17 대 INSERT 6이라는 분포와 일치한다. 한계는 프레임군 기준 귀속이며, 같은 Jackson 프레임이 예매 본문인지 웹훅 본문인지는 스택이 잘려 구분하지 못했다. 실제 호출 비율은 예매 생성 1,000(승자 57), 결제 요청 57, 상세 조회 87이다. 예열 구성과 근거는 [ADR 0032](../../adr/0032-warmup-traffic-mix.md)에 있다.

## 변경

- Gateway 예열: 한 회차 1,000건에 예매 승자 50·충돌 약 820·결제 요청 50·상세 조회 약 80을 인덱스 규칙으로 섞고(동시 1,000), 예매 생성은 좌석 1~4장 템플릿이다. 15회 × 1,000건은 그대로다. 요청이 서버 오류·연결 오류로 실패하면 최대 2회 다시 보낸다(아래 "실패 이력").
- core booking: 상세 조회·결제 요청 `@Primary` 데코레이터가 예열 principal의 `-1` 템플릿만 롤백 전용 트랜잭션의 격리 환경(음수 회차 `schedule`·`schedule_seat`·예매)으로 보낸다. 결제 요청은 실제 `RequestPaymentService`(예매 UPDATE·`PaymentRequested` 발행)를 실행하며 이벤트는 롤백과 함께 사라져 실제 PG 요청이 없다.
- core payment: `PaymentWarmupRequested` 이벤트를 받아 롤백 전용 트랜잭션에서 실제 결제 준비·수락·웹훅 승인(서명 검증·파싱·UPDATE·이벤트 발행)을 실행하고, 트랜잭션 밖에서 `GET /v1/transactions?date=1970-01-01`을 같은 `FakePgAdapter` RestClient로 보낸다. fake-pg는 변경하지 않았다.
- 보안: 전용 체인 허용 경로를 `POST /api/v1/bookings`, `POST /api/v1/bookings/-1/payments`, `GET /api/v1/bookings/-1`로 늘렸고 나머지는 `denyAll`이다.
- 변경하지 않은 것: 대기열·SSE, AOT, 운영 풀·컴파일 옵션·인스턴스 수.
- 한계: POST 본문 쓰기와 `HttpURLConnection` POST 출력 경로는 예열되지 않는다(운영 PG에 가짜 결제를 보낼 수 없어 GET만 보냈다). 탈최적화 중 PG 송신 계열 20건이 남았다.

## 검증

- 테스트 추가: 결제 요청·상세 조회가 실제 `BookingController` 핸들러를 거침(`MvcResult.handler`), `booking.*`·`payment.payment`·`payment.webhook_event`·`event_publication` 행 수 불변, `TestPg` 요청 0건, 예열 Redis 키 0건, 위조·역할·헤더 누락·템플릿 외 ID·경로(취소·웹훅 경로)·좌석 템플릿 외 거부, `PaymentWarmupWork`(웹훅 서명·파싱·승인 UPDATE·PG 조회 실제 경로) 롤백·PG 호출 불변, Gateway 요청 종류 비율·좌석 수 분포·재시도. 모두 통과했다.
- 전체 `run-tests.sh` 통과. `ktlintCheck detekt`는 첫 실행에서 `PaymentWarmupWork`의 미사용 파라미터(`UnusedParameter`)와 파일명 규칙 위반이 있어 `@EventListener(클래스)`로 받고 파일명을 고쳐 통과했다(`@Suppress`·baseline 없음). 기본 `check` 통과. Modulith 검증(`ModularityTests`)은 전체 테스트에 포함되어 통과했고 의존은 기존 booking→payment 이벤트 의존뿐이라 다이어그램은 순서만 달라져 되돌렸다.
- 예열 활성 E2E(`STARTUP_WARMUP_ENABLED=true ./scripts/e2e/run.sh`) 통과. core 6,451ms, Gateway 28,404ms·15회 · 15,000건·승자 750·결제 750·상세 1,215, payment 예열 750회·PG 조회 750회·실패 0.

### 실패 이력

JFR OFF 첫 시도(`20261116-155637-hot-seats`)에서 1회차가 "내부 예열 미완료"로 본 측정 전에 중단됐다. 첫 회차 570건째에 Gateway→core 22개 연결이 동시에 끊겨(`recvAddress failed with error(-104)`) 예열 요청이 500이 됐고 Gateway가 예열을 `failed`로 끝냈다. core 로그에는 오류가 없었다. 원인은 확정하지 못했다. 가설은 첫 회차의 동시 연결 폭주로 core accept 큐(Tomcat 기본 acceptCount)가 넘친 것이며, 호스트 `TcpExtListenOverflows`가 누적 117만으로 이 환경에서 흔하다는 정황만 있다. 이전 예열 결과(17번)에서는 같은 동시성에서 발생하지 않았고 이번 구성(결제·상세로 DB 연결을 더 쓰는 요청 추가) 이후 처음 관찰했다. 대응으로 예열 요청의 서버·연결 오류만 최대 2회 재시도하도록 했고(재시도 횟수는 로그의 `retries=`), 예열은 격리돼 재전송해도 데이터가 남지 않는다. 이후 측정 3회에서 `retries=0`이었으므로 재시도가 결과에 영향을 주지 않았다. 같은 시도의 2·3회차(p95 899·827ms)는 아래 표에 별도로 적는다.

### JFR 진단 (`20261116-155332-hot-seats/1`)

전체 예매 p95 849ms(성공 697ms), 정합성·SSE·정시 준비 통과, 300ms 미달. 예열은 core 6,549ms·Gateway 29,766ms에 완료했다. 분석 구간은 `readiness.json`의 시작 시각부터 p99 + 56ms까지로, 17번의 구간 길이(p99 + 56ms)와 같은 방식으로 추정했다(`booking-window.json`, 정확한 요청 종료 시각이 아니다). 결과는 `build/cold-jfr-diagnosis/trafficmix-core.tsv`·`trafficmix-gateway.tsv`다.

| 항목 | 17번(`20261116-140027`) | 이번 |
|---|---|---|
| core 탈최적화 | 203 | **52** |
| Gateway 탈최적화 | 13 | 14 |
| core 최상위 Interpreted | 36/155 (23.2%) | 20/143 (14.0%) |
| core 임의 프레임 Interpreted 표본 | 82 | 64 |
| Gateway 최상위 Interpreted | 1/109 (0.9%) | 1/120 (0.8%) |
| 구간 시작 로드 클래스 | core 31,307 / Gateway 18,004 | core 31,780 / Gateway 17,842 |

core 사유: unstable_if 24·class_check 8·speculate_class_check 7·bimorphic 6·unreached 4·null_check 3. 스레드별로는 요청 스레드 31, `task-*` 20(PG 송신: Jackson 본문 쓰기 7, RestClient 4, 기타), 기타 1이다. 이전에 가장 많던 Hibernate `Update/InsertCoordinator`·`resolveValueDescriptor`·`StringJavaType.wrap`은 상위에서 사라졌다. 남은 상위는 `StreamUtils$NonClosingOutputStream.write`·`AbstractSmartHttpMessageConverter.write`(PG 본문 쓰기와 응답 쓰기), `NioSocketImpl.read`, `WarmupIo.redisCommand`다. Gateway 탈최적화 14건은 대부분 Jackson 숫자 파싱·reactor-netty 풀로 이전과 비슷하다. JFR 진단은 한 번이며 표본 간 변동을 분리하지 않았다. 구간 시작 로드 클래스는 약 470개 늘었다.

### JFR OFF 성능

Gatling 힙 2g·1,000명·관측 ON·Scouter OFF·발생기 CPU 0-3 / 앱 4-15·기존 풀 설정, 앱 매회 새 기동이다.

```bash
./scripts/loadtest/run.sh --scenario hot-seats --tool gatling --gatling-heap 2g \
  --runs 3 --observability --no-scouter --internal-warmup \
  --booking-p95-ms 300 --k6-cpus 0-3 --app-cpus 4-15
```

| 회차 (`20261116-160339-hot-seats/<n>`) | 전체 p95 | 성공 p95 | 충돌 응답 p95 | 판정 |
|---|---|---|---|---|
| 1 | 760ms | 677ms | 762ms | 300ms 미달 |
| 2 | 772ms | 707ms | 773ms | 300ms 미달 |
| 3 | 750ms | 707ms | 753ms | 300ms 미달 |

세 회차 모두 정합성·SSE·정시 준비 통과, 실패 항목은 `toolExit`·`bookingP95`·`successP95`뿐이다. 모든 회차 core·Gateway 예열은 `completed`였고 payment 예열 실패 로그는 0건이다. 앞서 중단된 시도(`20261116-155637-hot-seats`)의 2·3회차는 전체 p95 899·827ms(성공 810·709ms)다(1회차는 위 예열 실패). 이전 3회(709·1,458·816ms)와 같은 조건이며 회차당 표본이 1개라 개선을 결론짓지 않는다. 다만 이번 5회 모두 750~899ms에 모였고 core 탈최적화가 203건에서 52건으로 줄었는데도 300ms에 닿지 않았다.

### 정리 확인

측정 종료 후 확인한 결과는 모두 0건이다. Redis `booking:warmup:*`·`queue:admission:warmup-*`, DB `booking.booking`(음수 회차·음수 회원)·`booking.schedule`·`schedule_seat`(음수 회차)·고아 `booking_seat`, `payment.payment`(주문 ID 2^62 이상·`warmup-` 멱등 키), `payment.webhook_event`(`evt_warmup_`·`txn_warmup_`), `event_publication` 예열 행과 미완료 행. fake-pg의 결제 접수는 회차별 62·57·65건으로 실제 결제 요청 수(`flow_payment_requested`)와 같고 환불은 0건이라 예열이 만든 PG 상태는 없다. 확인 스크립트는 scratchpad의 `iso.sh`다(DB는 측정이 매 실행 시작 시 재생성할 수 있어 마지막 실행 이후 상태 기준이다. 롤백 불변은 통합 테스트가 행 수 불변으로 보장한다).

### 컨테이너

예열 활성 E2E가 compose로 다시 띄운 fake-pg 컨테이너가 포트 18090을 점유해 JFR 진단 전에 정지했고, 측정을 모두 마친 뒤 `docker start`로 다시 시작해 원상 복원했다. 볼륨 삭제 등 파괴적 정리는 하지 않았다.

## 한계

- 탈최적화(core 203 → 52)와 지연(p95 약 760ms)의 관계가 약하다. 남은 지연의 원인은 이번 측정으로 분리하지 못했고 예열로 풀 수 있는 몫인지도 불명확하다.
- POST 본문 쓰기·`HttpURLConnection` POST 출력은 예열되지 않는다. JFR 상 PG 송신 계열 20건이 남았다.
- 예열 시간이 약 25초에서 28~30초로 늘었다(readiness 전, 상한 120초 안).
- JFR 진단 구간은 요청 종료 시각이 아니라 p99 기반 추정이고 진단은 1회다.
- 예열 요청도 Micrometer 지표에 기록되고 Redis rate limiter 경로는 예열에서 실행되지 않는다(17번과 같다).
- 첫 회차의 연결 끊김 원인은 확정하지 못했다.

## 산출물

- 테스트: `build/run-tests/gradle-test-out.txt`, 검사: `build/check-summary/gradle-out.txt`, E2E: `build/e2e/logs/`.
- JFR 진단: `build/loadtest/runs/20261116-155332-hot-seats/1`(집계 `build/cold-jfr-diagnosis/trafficmix-{core,gateway}.tsv`), JFR OFF: `build/loadtest/runs/20261116-160339-hot-seats/{1,2,3}`, 중단된 시도: `build/loadtest/runs/20261116-155637-hot-seats/{1,2,3}`.
- 러너 로그: `/tmp/claude-1000/-home-hong-dev-git-toy-ticketing/bfa2597f-25c9-46a0-adb1-9562a156b123/scratchpad/{jfr2-run,off2-run,off3-run}.log`.
