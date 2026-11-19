# hot-seats 1,000명 예매 생성 p95 300ms 도달 계획

- 작성일: 2026-11-14
- 상태: 장기 과제로 보류(로드맵 후속 항목). Step A~C 결과는 [문서 04](../performance/m5/04-1000-user-steps-a-c.md)에 있고, 이후 측정은 Gatling 계획 [hot-seats-gatling-p95-300ms](./hot-seats-gatling-p95-300ms.md)로 이어졌다

## Context

`docs/performance/m5-improvements.md`의 진단은 "1,000명 동시 도착은 앱 CPU 포화, 핫스팟 없음, 콜드 스타트는 주원인 아님"으로 끝났고 300ms 기준은 50명 조건으로 분리됐다. 보존된 산출물을 다시 분석한 결과 이 결론의 마지막 부분이 틀렸을 가능성이 높다.

- `build/loadtest/runs/20261114-131040-hot-seats/1/core.jfr`: 폭주 1초(13:12:04)의 core 실행 샘플 281건 중 **164건(58%)의 최상위 프레임이 Interpreted**다. 예매 생성 경로는 폭주 순간이 첫 호출이라 JIT 전 코드로 1,000건을 처리한다. core 약 8코어·약 1초 ÷ 1,000건 = 요청당 약 8ms CPU는 정상 상태 대비 한 자릿수 배 이상 크다. Gateway는 같은 구간 샘플이 80건이고 대부분 컴파일된 코드라 core를 기다리는 쪽이다.
- `20261114-060017-hot-seats/1`: 폭주 1회 워밍업만으로 p50 1,215 → 611ms, 최소 413 → 62ms. p95만 안 줄어 "효과 없음"으로 읽혔지만, 1,000건 1회는 C2 임계(수천~만 회)에 못 미친다.
- 패자(약 940명)는 멱등 키 DB 조회를 2번 한다(진입 시 `existing`, 선점 실패 후 다시). 풀 10에서 직렬화된다.

합의한 판정 조건: **콜드(앱 새로 기동, 워밍업 요청 없음) + AOT 캐시 기동 설정**. 충분히 예열한 실행은 상한 확인용 진단으로만 쓴다.

## 접근

변경 하나씩 적용하고 조건당 콜드 3회로 판정한다(측정 계약 3항). 공통 조건: `hot-seats --observability --no-scouter`, 코어 4/12, 풀 10, JFR 비활성. 기준 계열은 `20261114-131726`(p95 1,688·1,575·1,766)이며 코드가 달라지면 같은 날 기준 3회를 다시 잰다.

### Step A. 상한 진단: 충분히 예열하면 300ms에 닿는가 (코드 변경 없음, 각 1회)

- `scripts/loadtest/run.sh`에 `--warmup-rounds N`(기본 1, `--warmup`과 함께 사용)을 추가한다. 기존 워밍업 블록(약 216~224행)을 N회 반복하고 회마다 새 회차를 시드하며 `warmup-k6-summary-<n>.json`으로 남긴다. `env.txt`에 횟수를 기록한다.
- 1,000명으로 5회·15회 예열 후 측정하고, 회차별 p50·p95·최소 추이를 본다. 15회 실행 1회는 `--jfr`로 폭주 구간 Interpreted 비율이 내려갔는지 확인한다(JFR 실행 수치는 다른 실행과 섞지 않는다).
- **판단 지점**: 예열 상태 p95가 300ms 근처로 내려가면 Step B·C로 간다. 예열해도 한참 위면(예: 600ms 이상) AOT로는 못 닿으므로 Step B 후 Step D의 후보를 JFR 근거로 먼저 좁힌다. 결과와 무관하게 수치를 문서에 남긴다.

### Step B. 패자 경로 DB 조회 줄이기 (core)

`core/src/main/kotlin/io/github/ticketing/booking/application/service/CreateBookingService.kt`

- `create()`의 진입 시 `existing` 조회를 없애고 `find-copies → hold → (실패 시 existing ?: 409) → persist` 순서로 바꾼다. 멱등 재호출은 이미 있는 두 경로가 처리한다: 자기 좌석이 선점·판매 상태면 `holdOrExisting`, 좌석이 풀린 뒤(만료·취소)면 `persist`의 `(member_id, idempotency_key)` 유니크 위반 → 선점 해제 → 기존 예매 반환. 신규 요청당 DB 조회가 2회 → 패자 1회·승자 0회가 된다.
- 달라지는 동작: 만료·취소된 예매의 키로 재호출하면 좌석이 잠깐 선점됐다 풀린다(스트림에 HELD·해제 이벤트). 응답은 기존과 같다(200, 기존 예매). `docs/architecture/flows.md`의 예매 생성 순서 서술이 있으면 같이 고친다.
- `stage()`가 호출마다 `Timer.builder`를 만드는 것을 단계별 Timer 필드로 바꾼다.
- 테스트: 기존 멱등·동시 요청 테스트 유지. 추가로 (1) 취소된 예매 키 재호출 시 기존 예매 반환과 선점 해제, (2) 선점 실패 패자는 409이고 승자 예매는 그대로임을 확인한다.
- 측정: 콜드 1,000명 3회, 기준 3회와 비교.

### Step C. AOT 캐시 기동 (runner·문서)

JDK 25.0.5, Spring Boot 4.1.2. 구현 전 공식 문서로 옵션을 확인한다(Spring Boot "Ahead-of-Time Cache"/jar 풀기, JEP 483·514·515).

- `run.sh`에 `--aot-cache` 추가:
  1. bootJar 뒤 core·gateway(효과가 보이면 realtime)를 `java -Djarmode=tools -jar <jar> extract --destination <dir>`로 푼다(중첩 jar는 캐시 대상이 안 된다).
  2. 학습 기동: `-XX:AOTCacheOutput=<name>.aot`로 앱을 띄우고 hot-seats 부하를 별도 회차로 여러 번(Step A에서 정한 횟수) 보낸 뒤 SIGTERM으로 정상 종료해 캐시를 쓴다. 학습은 실행 루트에서 1회만 한다.
  3. 측정 기동: 매 회 새 프로세스를 `-XX:AOTCache=<name>.aot`로 띄운다. 워밍업 요청은 보내지 않는다. 캐시가 실제로 쓰였는지 `-Xlog:aot` 로그로 확인하고, 못 쓰면 실행을 실패 처리한다.
  4. `env.txt`에 AOT 사용 여부·학습 횟수·캐시 파일 크기를 기록한다. JFR·Scouter 에이전트 옵션이 학습과 측정에서 다르면 캐시가 무효가 될 수 있으므로 같은 옵션으로 맞춘다.
- 결정 기록: `docs/adr/0026-aot-cache-startup.md`(대안: 기동 시 자체 워밍업 호출, CRaC, 측정 기준 유지)와 `docs/performance/loadtest-runbook.md`에 실행 절차를 추가한다. 앱 코드와 `application.yml`은 바꾸지 않는다.
- 측정: Step B 코드 위에서 콜드 1,000명 3회(AOT 없음/있음). 1회는 `--jfr`로 Interpreted 비율과 `ClassLoader.loadClass` pinning 감소를 확인한다. 50명 콜드 3회도 다시 재서 기존 247·305·258ms와 비교한다.

### Step D. 아직 300ms 초과일 때 (근거가 있는 것만, 하나씩)

Step C의 JFR·단계 타이머에서 남은 몫이 큰 순서로 고른다. 미리 구현하지 않는다.

- Gateway: 입장 토큰 조회와 rate limiter의 Redis 왕복 2회를 동시에 실행(`AdmissionTokenFilter`, `RateLimitFilter`). Gateway도 AOT 캐시 적용 여부 확인.
- core Hikari 풀 크기(10 → 대조값). 기존 문서의 "기본값 유지" 판단을 바꾸는 것이므로 대조 결과를 먼저 보고한다.
- k6 발생기: 4코어에서 1,000 VU 동시 송신 자체의 지연 하한 확인(k6 코어 대조).
- 선점 실패 시 DB 멱등 조회까지 없애는 Redis 멱등 표식은 멱등 계약을 바꾸므로 사용자 합의와 ADR이 먼저다.

## 문서 갱신

- `docs/performance/m5-improvements.md`: 인터프리터 비율 근거, "콜드 스타트는 주원인이 아니다" 정정, Step A~C의 모든 실행 값·디렉터리·실패 이력.
- `docs/backup/plans/m5-2-improvements.md` 측정 계약 6항: 1,000명 조건의 판정 방식(콜드 + AOT 캐시)을 결과에 맞춰 고친다. 미달이면 기준 분리를 유지하고 수치를 보고한다.
- `docs/roadmap.md` 체크박스는 기준을 실제로 충족했을 때만 갱신한다.

## 검증

순차 실행하고 Gradle 검사와 부하를 겹치지 않는다.

```bash
./scripts/agent/test/run-tests.sh
./scripts/agent/test/check-summary.sh ktlintCheck detekt
./scripts/e2e/run.sh
./scripts/agent/test/run-summary.sh -- bash -n scripts/loadtest/run.sh
./scripts/loadtest/run.sh --scenario smoke --observability
./scripts/loadtest/run.sh --scenario hot-seats --observability --no-scouter --warmup --warmup-rounds 15   # Step A
./scripts/loadtest/run.sh --scenario hot-seats --runs 3 --observability --no-scouter                     # Step B 전후
./scripts/loadtest/run.sh --scenario hot-seats --runs 3 --observability --no-scouter --aot-cache         # Step C
./scripts/agent/docs/check-docs.sh
```

매 실행에서 확인: 정시 준비 1,000명, k6 종료 코드와 threshold, `http_req_failed`, SSE 성공률, `integrity.json`(중복 판매·DB/Redis 불일치 0건). 정시 준비가 1,000명 미만인 실행은 합격 근거로 쓰지 않는다. 합격 판정은 3회 모두 p95 < 300ms일 때만 한다.
