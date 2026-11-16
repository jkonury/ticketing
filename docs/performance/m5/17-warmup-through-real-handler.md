# M5 내부 예열의 실제 예매 핸들러 경유

> 허브: [M5-2 개선 및 측정 인계](../m5-improvements.md) · 이전: [예열 호출량 확대](16-startup-warmup-burst-volume.md) · 결정: [ADR 0031](../../adr/0031-warmup-through-real-booking-handler.md)

## 결론

예열 HTTP 요청이 별도 예열 컨트롤러 대신 Gateway 운영 라우트(`core-bookings-create`)와 core의 실제 `BookingController.create`·`hasRole('USER')` 인가를 거치도록 바꿨다. 기능 검사는 통과했고 예열은 이전과 같은 15회 · 15,000건을 완료했다. 그러나 JFR OFF 3회 전체 예매 p95는 **709·1,458·816ms**로 300ms 목표에 모두 미달이다. 이전(741·843·853ms)보다 낫다고 말할 수 없고, 2회차는 오히려 높다. 예매 구간 `jdk.Deoptimization`은 core **203건**(이전 198), Gateway **13건**(이전 46)이며 core의 최상위 Interpreted 표본 비율은 23.2%로 거의 같다. "예열과 본 요청의 호출 경로 차이가 core 탈최적화의 원인"이라는 가설은 core에서는 지지되지 않았다. Gateway 쪽 감소는 관찰했으나 지연 감소와의 인과는 확인하지 못했다. 순수 콜드 회귀는 생략했다.

## 변경

- Gateway 예열 요청: `POST /api/v1/bookings`(Gateway 자신의 포트). 운영 JWT·입장 토큰(Redis 실조회)·본문 캐시 필터와 라우트를 거친다. 한도 필터만 예열 표시 요청에서 건너뛴다.
- core: 예열 전용 컨트롤러를 삭제했다. `@Primary` `CreateBookingUseCase` 데코레이터가 예열 principal일 때만 음수 회차 격리 환경(DB 롤백·`booking:warmup:` 키·30초 TTL)으로 보낸다. 승자 201, 패자 409 `SEAT_ALREADY_HELD`는 실제 핸들러·예외 처리 경로의 응답이다.
- 보안 경계(상세는 ADR 0031): 서버 역할 JWT와 core가 기동 시 만든 일회성 비밀(메모리 전용·발급 1회·3분·readiness 이후·30,000건·동시 1,000)을 모두 만족할 때만 `ROLE_USER` 예열 principal. 전용 보안 체인은 `POST /api/v1/bookings` 외 `denyAll`. Gateway는 외부의 예열 헤더를 보유 자격과 불일치하면 제거하고 `/internal/**` 라우트를 삭제했다. 고정 템플릿이 아니면 403이다.
- 입장 토큰: `queue:admission:warmup-<UUID>`(회원 -1·회차 1, TTL 3분)를 만들고 종료 시 삭제한다.
- 변경하지 않은 것: 결제·조회·대기열·SSE 예열 추가 없음, AOT·운영 풀·컴파일 옵션·인스턴스 수.
- 측정 도구: `build/cold-jfr-diagnosis/Summarize.java`에 예매 구간 `jdk.Deoptimization` 건수·reason/action·상위 메서드와 구간 시작 시점 로드 클래스 수(`DEOPT_*`, `CLASSES_AT_WINDOW_START`)를 추가했다. 기존 출력 형식은 유지했고 기존 tsv는 덮어쓰지 않았다. 이전 JFR(`20261116-132736`)에 적용하면 core 198건으로 기존 집계와 일치한다.

## 검증

- 테스트 추가: 예열 승자·패자가 실제 `BookingController` 핸들러를 거침(`MvcResult.handler` 확인), 위조 헤더·서버 역할 누락·USER 역할 위조·일반 JWT 거부, 예매 생성 외 경로·비템플릿 본문·키 거부, 서버 역할만으로 일반 핸들러 접근 불가, 예열 기간(4분 경과) 후 거부, 자격 재발급 거부, 실제 예매·음수 회차 예매·예열 Redis 키 미변경. Gateway: 외부 예열 헤더 제거, 설치된 자격만 표시, 트래픽 헤더·경로·집계. 모두 통과했다.
- 전체 테스트 통과. `ktlintCheck detekt` 통과(중간에 Gateway 필터의 detekt ReturnCount 위반을 코드 구조로 해결했고 Suppress·baseline은 없다). 기본 `check`는 첫 실행에서 `:gateway:test`의 상태 코드 단언 1건이 실패했고(원인 테스트 미식별, 결과 파일이 재실행으로 덮임) 재실행에서 통과했다. 통과를 확인했을 뿐 원인은 확정하지 못했다.
- 예열 활성 E2E(`STARTUP_WARMUP_ENABLED=true ./scripts/e2e/run.sh`) 통과. core 6,448ms·직접 200회, Gateway 25,320ms·15회 · 15,000건·승자 750·DB 15,750회·Redis 74,250회로 이전과 같은 호출량이다.

### JFR 진단 (`20261116-140027-hot-seats/1`)

전체 예매 p95 650ms(성공 559ms), 정합성·SSE·정시 준비 1,000/1,000 통과, 300ms 미달. 예열은 core 6,569ms·Gateway 26,459ms에 완료했다. 분석 구간은 이전과 같고 결과는 `build/cold-jfr-diagnosis/realhandler-core.tsv`·`realhandler-gateway.tsv`다.

| 항목 | 이전(`20261116-132736`) | 이번 |
|---|---|---|
| core 탈최적화 | 198 | 203 |
| Gateway 탈최적화 | 46 | 13 |
| core 최상위 Interpreted | 45/195 (23.1%) | 36/155 (23.2%) |
| Gateway 최상위 Interpreted | 3/162 (1.9%) | 1/109 (0.9%) |
| 구간 시작 로드 클래스 | 미집계 | core 31,307 / Gateway 18,004 |

core 사유: class_check 79·unstable_if 72·bimorphic_or_optimized_type_check 37·그 외 15. 이전의 상위 메서드(`AopUtils.invokeJoinpointUsingReflection`, `ReflectJvmMapping.getJavaMethod`, `AnnotatedMethodParameter.getParameterAnnotations`)는 이번 상위 15위에 없다. 대신 Jackson 캐시, Micrometer·Tracing 핸들러, Hibernate 삽입·타입 비교 메서드가 상위다. 같은 기준의 이전 집계에서도 Micrometer·Jackson이 있었으므로 일부는 공통이다. 외부 15회 예열 참고값은 core 1건·Gateway 0건이므로 격차는 여전하다. JFR 진단은 한 번이며 표본 간 변동을 분리하지 않았다.

### JFR OFF 성능

Gatling 힙 2g·1,000명·관측 ON·Scouter OFF·발생기 CPU 0-3 / 앱 4-15·기존 풀 설정, 앱 매회 새 기동이다.

```bash
./scripts/loadtest/run.sh --scenario hot-seats --tool gatling --gatling-heap 2g \
  --runs 3 --observability --no-scouter --internal-warmup \
  --booking-p95-ms 300 --k6-cpus 0-3 --app-cpus 4-15
```

| 회차 (`20261116-140254-hot-seats/<n>`) | 전체 p95 | 성공 p95 | 충돌 응답 p95 | 판정 |
|---|---|---|---|---|
| 1 | 709ms | 677ms | 709ms | 300ms 미달 |
| 2 | 1,458ms | 780ms | 1,461ms | 300ms 미달 |
| 3 | 816ms | 711ms | 818ms | 300ms 미달 |

2회차 전체 p95는 패자(충돌) 응답의 p95가 높아져 나타났고 성공 응답은 780ms다. 원인은 분리하지 못했다. 세 회차 모두 정합성·SSE·정시 준비 통과, 실패 항목은 `toolExit`·`bookingP95`·`successP95`뿐이다. 모든 회차 core·Gateway 예열은 `completed`였다. 이전 3회(741·843·853ms)와 같은 조건이나 회차당 표본이 1개이고 변동이 커서 개선·악화를 결론짓지 않는다.

### 격리 확인

종료 후 `booking:warmup:*` Redis 키 0건, `queue:admission:warmup-*` 키 0건, DB `booking.booking`의 음수 회차 예매 0건이다.

### 컨테이너

예열 활성 E2E가 compose로 다시 띄운 fake-pg 컨테이너가 포트 18090을 점유해 첫 JFR 시도가 실패했다(측정 전 종료, 산출물 `20261116-133016` 이후 새 디렉터리 없음). 이 컨테이너를 정지하고 JFR 진단과 JFR OFF 3회를 수행한 뒤 다시 시작해 원상 복원했다. 볼륨 삭제 등 파괴적 정리는 하지 않았다.

## 한계

- 예열 요청도 Micrometer 지표에 `/api/v1/bookings`로 기록된다.
- Redis rate limiter 경로는 예열에서 실행되지 않는다(예열 표시 요청만 건너뜀).
- 전용 보안 체인은 운영 체인과 같은 필터 클래스를 쓰는 별도 인스턴스다.
- Hibernate·Micrometer 등 남은 탈최적화 원인과 지연의 관계는 미분리다. 예열 호출 경로 통일만으로는 core 탈최적화가 줄지 않았다.

## 산출물

- 테스트: `build/run-tests/gradle-test-out.txt`, 검사: `build/check-summary/gradle-out.txt`, E2E: `build/e2e/logs/`.
- JFR 진단: `build/loadtest/runs/20261116-140027-hot-seats/1`, JFR OFF: `build/loadtest/runs/20261116-140254-hot-seats/{1,2,3}`.
- 러너 로그: `/tmp/claude-1000/-home-hong-dev-git-toy-ticketing/bfa2597f-25c9-46a0-adb1-9562a156b123/scratchpad/jfr-run.log`, `off-run.log`.
