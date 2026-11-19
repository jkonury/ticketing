# 내부 예열 3.2 구현 인계 (2026-11-16)

- 상태: 완료 (보관). 후속 진행은 [내부 예열 콜드 900ms 계획](../../plans/hot-seats-internal-warmup-cold-900ms.md)을 따른다

## 후속 세션 완료 상태 (2026-11-16)

아래 본문은 재개 전 인계 기록이다. 최초 구현 결과는 [구현·검증 보고](../../performance/m5/15-layered-startup-warmup.md), 이후 사용자 요청에 따른 강화 결과는 [HTTP 예열 확대 보고](../../performance/m5/16-startup-warmup-burst-volume.md)를 따른다.

- 최종 컴파일·전체 테스트·ktlintCheck·detekt·예열 활성 E2E·셸/Python 회귀 검증을 완료했다. 전체 테스트에서 드러난 기존 PaymentFlowTests의 주문 ID 접두사 조회 충돌도 수정했다.
- 내부 예열 JFR 진단 1회, JFR OFF 독립 3회를 완료했다. core Interpreted 31.8%, p95 1,505·1,448·1,242ms로 계획의 JFR·300ms 완료 기준은 미달이다. 정합성·오류·SSE 검사는 통과했다.
- 순수 콜드 회귀는 **사용자가 스킵을 요청**해 실행하지 않았다. 예열 키·DB 음수 회차 예매는 0건이며 E2E용 fake-pg 컨테이너를 복원했다.
- 최초 구현은 `faeb0cc`로 커밋했다. 이후 Gateway 경유를 15회 × 1,000건으로 늘리고 운영 본문 캐시·core MVC 바인딩·검증을 보강했다. 최종 전체 테스트·품질 검사·E2E가 통과했으며 JFR core Interpreted 23.1%, JFR OFF 3회 p95 741·843·853ms로 목표 미달이다. 사용자가 강화 변경도 커밋하도록 요청했다.

## 사용자 요청과 현재 상태

- 사용자 요청: **“커밋하고 3.2 계층형 내부 예열 구현해”**. 이전 중단 조건을 넘어 실제 DB 롤백·Redis 격리를 포함한 3.2 구현을 승인했다. 추가 승인 질문을 반복하지 않는다.
- 이후 요청: 컨텍스트 사용량 때문에 작업을 중단하고 다음 세션으로 넘긴다. 이 문서를 작성한 뒤 구현·검사를 더 진행하지 않았다.
- 이전 진단 문서만 `5ff3f31` (`docs(loadtest): 콜드 JVM 옵션과 JFR 진단 결과 기록`)로 커밋했다. 현재 main은 origin/main보다 1커밋 앞선다.
- **3.2 구현은 미커밋 작업 트리에 있다. 미완료이며 성능 통과를 주장하면 안 된다.**
- 로드맵 300ms 체크는 아직 미완료다. 구현 후속 보고서와 로드맵 갱신도 남았다.

## 먼저 읽을 범위

AGENTS.md, backend 규칙을 따른다. 아키텍처·로드맵 확인 뒤 아래 자료와 현재 diff 범위부터 읽는다. 이전 전체 로그나 JFR을 먼저 다시 출력하지 않는다.

- [구현 계획](../../plans/hot-seats-internal-warmup-cold-900ms.md): 3.2 구현 승인과 기본 설정을 반영했다.
- [ADR 0029](../../adr/0029-layered-startup-warmup.md): 이번 구현 선택과 취소·격리·관측의 한계.
- [이전 JFR 진단](../../performance/m5/14-cold-jfr-diagnosis.md): 기준 1.0 / 0.1 콜드 p95 1,664 / 2,006ms. core 최상위 Interpreted 53.1% / 41.0%, 기존 외부 15회 예열 0/58. 서로 다른 계열을 합치지 않는다.

## 구현된 작업 트리

### core

`core/src/main/kotlin/io/github/ticketing/booking/adapter/provided/warmup/` 아래:

- `BookingWarmupRunner.kt`: 기본 비활성, 앱별 상한 30초(최대 2분), Virtual Thread 2개. 메모리 10,000회 뒤 실제 호출 200회, 승자 5%·패자 95%, 작업자별 50ms 대기. 상한·실패 때 취소하고 기동 계속. 완료 로그에 반복 수·시간·DB/Redis 호출 수.
- `BookingWarmupWork.kt`: Jackson 실제 요청 DTO·Bean Validation·Booking 생성·실제 응답 DTO 직렬화·jOOQ 렌더링·Lettuce CommandArgs 인코딩. 실제 CreateBookingUseCase 호출은 새 음수 UUID 회차마다 캐시 사본을 넣고 승자는 실제 JPA flush 후 rollback-only, 패자는 실제 선점 충돌+멱등 조회. finally에서 해당 Redis 키 삭제.
- `WarmupIo.kt`: 활성화 시에만 DataSource BeanPostProcessor로 JDBC execute 호출을 센다. ThreadLocal 범위만 집계하며 원본 예외를 풀어 전달한다. Redis는 템플릿 명령 호출을 명시적으로 센다. Lua 내부 명령·EVALSHA 재전송은 별도 집계하지 않는다. timeout 수치는 취소 시점 스냅샷.
- `BookingWarmupController.kt`: 활성화 시만 POST `/internal/startup-warmup` 생성. `STARTUP_WARMUP` JWT 역할 필요. core ready 뒤, 빈 생성 후 2분 이내·최대 8회·동시 2개. 실제 승자 201·패자 409와 집계 반환.

관련 변경:

- RedisSeatHoldAdapter·ScheduleSeatCache: 음수 회차는 `booking:warmup:` 키 접두사. 캐시·선점 TTL 30초. hold/release Lua가 스냅샷·Stream TTL도 생성과 함께 적용. 일반 realtime Stream 접두사와 다르다.
- CreateBookingRequest.scheduleId에 Positive 검증을 추가해 외부 음수 회차 접근 차단.
- core 설정에 readiness probes와 STARTUP_WARMUP_ENABLED / MAX_DURATION / LOCAL_ITERATIONS 매핑.
- 원래 CreateBookingService는 변경하지 않았고 생성 자체는 Modulith 이벤트를 발행하지 않는다. 예열에서 결제·확정 호출은 하지 않는다.

### gateway / 러너

- `gateway/.../warmup/GatewayWarmupRunner.kt`: 실제 JWT 디코더, 운영 필터와 공유하는 Bearer·본문 파서, Jackson 반복. core readiness 대기 후 자기 HTTP 서버의 전용 경로로 8회 요청하여 GlobalFilter·SCG 라우팅·실제 HTTP 연결 풀 경유. 승자 1회·패자 7회, 50ms 간격.
- 기존 JWT·Admission 필터에서 파서만 internal 함수로 추출해 공유했다. GatewayRoutes는 예열 활성화 시만 내부 경로를 core로 라우팅한다.
- Gateway가 기존 JWT 비밀 키로 STARTUP_WARMUP 역할·120초 만료 서버 토큰을 만든다. 토큰 로그나 브라우저 노출 없음.
- reactive 작업에 전체 timeout 적용. ApplicationRunner 기동 스레드만 Future.get으로 완료 대기한다. 이벤트 루프 블로킹은 하지 않는다. 이 기동 대기 예외를 backend 규칙과 ADR에 명시했다.
- `scripts/loadtest/run.sh --internal-warmup`: 외부 warmup·AOT와 동시 사용 금지. core·gateway에 활성화 값을 명시적으로 전달하여 기본 순수 콜드에서는 환경 변수 상속으로 예열이 켜지지 않게 한다. 완료 로그를 env.txt에 기록.
- loadtest·E2E의 core/gateway health 대기를 `/actuator/health/readiness`로 바꿨다. 다른 앱은 기존 health 유지.

## 검증 현황 — 시점을 구분할 것

1. core/gateway 컴파일은 초기 구현에서 통과했다.
2. `./scripts/agent/test/run-tests.sh -Pkotlin.compiler.execution.strategy=in-process` **전체 테스트 통과**. 당시 실제 롤백·키 삭제·TTL·일반 SSE 키 미생성·이벤트 미발행·서버 역할 보호·비활성 경로 404·음수 회차 거부·시간 상한·JWT 검증 테스트가 추가된 상태였다.
3. 그 뒤 `check-summary.sh ktlintCheck detekt`는 **실패**했다. core 4개 MagicNumber, gateway 3개 MagicNumber + run LongMethod(62>60).
4. 지적을 고치고 추가 정리했다: 상수화, Gateway localWarmup 추출, core 홀수 localIterations 처리, 실제 응답 DTO 직렬화, 작업 중간 interruption 체크.
5. 마지막 `ktlintFormat`은 **성공**했다. 로그 `/tmp/ticketing-command.qKU8LSGm`. **마지막 수정 이후 컴파일·전체 테스트·detekt 재실행은 아직 하지 않았다.**
6. E2E, 내부 예열 부하/JFR, 순수 콜드 회귀, 문서 검사는 아직 실행하지 않았다.

실패 로그는 `build/check-summary/gradle-out.txt`(마지막 detekt), 전체 테스트 로그는 `build/run-tests/gradle-test-out.txt`. 로그 전체 출력 대신 오류 위치만 좁혀 읽는다.

초기 컴파일에서 Kotlin daemon의 `/home/hong/.local/share/kotlin/daemon` 쓰기 제한이 보였으나 fallback으로 이후 컴파일은 성공했다. 검사에는 `-Pkotlin.compiler.execution.strategy=in-process`를 사용해 불필요한 daemon 경로 접근을 피했다. 전역 설정·권한 우회는 하지 않았다.

## 다음 세션에서 이어갈 순서

1. 현재 상태·좁은 diff를 확인하고 마지막 수정의 컴파일·ktlintCheck·detekt를 마친다. 새 Suppress나 baseline 없이 고친다.
2. 필요 시 전체 테스트를 다시 실행한다(마지막 수정이 테스트 이후이므로 필요). 사용자 지정 필터가 없어 테스트 필터를 쓰지 않는다. Gradle 검사끼리 순차 실행한다.
3. 실제 기동 검증: 예열 활성 E2E. 예: `STARTUP_WARMUP_ENABLED=true ./scripts/e2e/run.sh`를 run-summary로 감싼다. E2E가 Compose fake-pg를 기동하면 이후 부하에서 18090을 점유하지 않게 기존 절차대로 일시 중지·복원한다.
4. 별도 JFR 진단: `./scripts/loadtest/run.sh --scenario hot-seats --tool gatling --gatling-heap 2g --runs 1 --observability --no-scouter --internal-warmup --jfr --booking-p95-ms 300 --k6-cpus 0-3 --app-cpus 4-15`.
5. core/gateway 완료 로그, env.txt 집계, Redis 예열 키 잔존 여부·DB 음수 회차 예매 잔존 여부, 정합성·SSE·준비 1000명, timeout 시 상태를 확인한다. 취소는 이미 전송한 I/O를 강제로 멈추지 못하므로 TTL·롤백이 최종 격리 수단이다.
6. 순수 콜드 회귀를 별도로 측정한다. 3.3의 독립 3회 성능 판정을 수행한다면 JFR OFF 내부 예열 계열과 순수 콜드를 구분해 기록한다. 300ms 미달이면 실패 그대로 남기고 체크하지 않는다.
7. 후속 보고서(다음 번호 15), 허브·로드맵·계획 상태를 실제 결과에 맞춰 갱신한다. ADR·아키텍처·backend 규칙 변경을 포함해 문서 검사를 수행한다.
8. 최종 diff 확인 후 구현·검사 결과와 미달/미수행을 보고한다. 이전 진단 커밋은 이미 완료했다. 새 구현 커밋을 원 요청에 포함해 진행할지 기존 대화 맥락을 판단하되, 커밋했다고 거짓 보고하지 않는다.

JFR 비교를 위한 로컬 분석 코드는 `build/cold-jfr-diagnosis/`에 있다. window.py가 Gatling 원시 로그의 예매 요청 구간을 만들고 Summarize.java가 ExecutionSample 최상위 Java 프레임의 Interpreted 비율 등을 집계한다. 기존 비교 자료는 `build/loadtest/runs/20261115-221926-hot-seats/1`, 새 콜드 자료는 `20261116-122616-hot-seats/1`·`20261116-122827-hot-seats/1`이다. 기존 스크립트의 분석 대상 목록을 바꿀 때 과거 결과를 덮어쓰지 않는 방식으로 확장한다.
