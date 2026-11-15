# 0028. 기동 시 내부 예열과 콜드 판정 조건

## 상태

제안 (2026-11-15). 구현은 완료했고 효과 측정은 하지 않았다. 측정 결과로 채택 여부를 확정한다.
[ADR 0026](0026-aot-cache-startup.md)이 기각한 "기동 시 자체 워밍업 호출"을 아래 조건으로 다시 연다.

## 맥락

1,000명 hot-seats 예매 생성 p95 300ms는 순수 JIT 콜드에서 근거상 닿지 않는다.
콜드 B 1,385ms(k6)·1,855ms(Gatling), 콜드 + AOT 861~1,167ms이고, 15회 외부 예열 후에야 274~383ms다.
폭주 구간 core 샘플의 56~63%가 Interpreted이며 AOT 캐시는 이 비율을 바꾸지 못했다([근거](../performance/m5/04-1000-user-steps-a-c.md)).
런타임 교체(GraalVM native image, CRaC)는 Scouter와 호환되지 않고 jOOQ·Hibernate·Modulith 검증 부담이 커서 이번에는 다루지 않는다.

## 검토한 대안

- 순수 콜드 유지: 근거상 300ms가 불가능해 판정할 수 없는 목표가 된다.
- 외부 워밍업 요청: 외부 부하가 프로파일을 채우므로 콜드가 아니다.
- stub 포트로 예열: 타입 프로파일이 오염되어 실제 어댑터가 들어오면 deopt된다.
- 기동 시 내부 예열(채택): 외부에서는 readiness 전의 기동 과정이다. 쿠버네티스에서 readiness gate 뒤에 트래픽을 받는 운영 방식과 같다.

## 결정

- **판정 조건 재정의.** "콜드"는 앱을 새로 기동하고 외부 워밍업 요청이 없는 상태다. 앱이 readiness 전에 스스로 하는 예열은 콜드에 포함한다.
  AOT 캐시 허용은 ADR 0026 그대로다. 순수 콜드(내부 예열 없음)는 회귀 기준(1,500ms)으로 계속 잰다. 두 계열의 수치를 섞어 개선율을 계산하지 않는다.
- **기본값은 꺼짐.** `ticketing.warmup.enabled=true`(환경 변수 `TICKETING_WARMUP_ENABLED`)일 때만 core가 `ApplicationRunner`에서 예열한다.
  부하 러너는 `--internal-warmup`으로 켠다. 테스트와 일반 기동은 바뀌지 않는다.
- **readiness 게이트.** 예열이 끝나기 전에는 `WarmupHealthIndicator`가 `OUT_OF_SERVICE`를 내서 `/actuator/health`가 UP이 아니다. 러너의 health 대기가 그대로 예열 완료를 기다린다.
  시간 상한(기본 40초)을 넘기면 계층 2를 중단하고 ready로 전환하며, 예열이 예외로 실패해도 기동을 막지 않는다.
- **계층형 예열.**
  - 계층 1(공유 자원 0, 많이 반복): 요청 JSON 역직렬화, Bean Validation, `Booking` 도메인 규칙, 응답·409 `ProblemDetail` 직렬화, jOOQ SQL 렌더링(`DSLContext.render`).
  - 계층 2(실제 어댑터, 적게 반복): 실제 `CreateBookingUseCase.create()`를 부른다. 동시성·초당 상한·시간 상한을 둔다. 승자 1건당 패자 15건으로 실제 폭주의 승패 비율(약 6%)에 맞춘다.
- **공유 자원 격리.** DB는 롤백 전용 바깥 트랜잭션으로 호출해 남기지 않는다(`saveAndFlush`가 INSERT와 유니크 제약 경로를 실제로 탄다).
  Redis는 실제 회차와 겹치지 않는 예열 회차 ID(`9_000_000_000_000_000_000` 이상)를 쓰고, 좌석 사본 캐시(ADR 0025)·선점·스냅샷·스트림 키를 회차마다 지운다.
  예열 회원 ID는 음수, 멱등 키는 `warmup-<회차>-<번호>`다. 예매 생성은 이벤트를 발행하지 않아 롤백한 예매가 다른 모듈로 나가지 않는다. 이 전제는 `CreateBookingService`가 바뀌면 다시 확인한다.
- **설계 규칙 준수.** 예열 코드는 booking 모듈 안에 두고 포트(`WarmupFixturePort`, `QueryRenderWarmupPort`)로 Redis·jOOQ에 접근한다. Modulith 경계와 ArchUnit 규칙을 건드리지 않는다.
- **범위 밖.** Gateway 내부 예열, `-XX:CompileThresholdScaling` 등 JVM 옵션의 채택(러너의 `--core-jvm-opts`·`--gateway-jvm-opts`로 대조 측정만 한다)은 이 결정에 포함하지 않는다.

## 결과

기동이 예열 시간만큼(상한 40초 + 계층 1) 길어지고, 예열 중 DB는 롤백되는 INSERT를, Redis는 키 쓰기·삭제를 소량 받는다. 호출 수와 소요 시간은 core 로그의
`internal-warmup:` 줄에 남고 러너가 `env.txt`에 옮긴다. 예열 요청은 `ticketing.booking.create.stage` 등 앱 지표에도 기록되므로, 서버 지표는 본 부하 직전·직후 누적 카운터의 차분(러너 기본 방식)으로만 해석한다.
롤백 전용 경로는 실제 커밋 경로를 예열하지 못한다. 폭주 구간 Interpreted 비율이 15회 외부 예열 수준(0% 근처)으로 내려가는지, 예열 포함 기동 3회 p95 중앙값이 300ms 미만인지는 측정으로 확인한다.
외부 15회 예열로도 300ms에 닿지 못하면 내부 예열로도 닿지 못한다.
