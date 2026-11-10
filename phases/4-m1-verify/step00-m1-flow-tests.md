# Step 0: m1-flow-tests

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `core/src/test/kotlin/io/github/ticketing/IntegrationTestSupport.kt`, `TestcontainersConfiguration.kt`
- `core/src/test/kotlin/io/github/ticketing/member/MemberAuthIntegrationTests.kt` (가입·로그인 API 사용법)
- 기존 테스트 fake: `rg -n 'class .*Fake.*PgPort|@Primary' core/src/test`로 PG fake와 가변 `Clock` 빈을 찾아 재사용한다.
- 컨트롤러 경로: `rg -n '@(Post|Get)Mapping|@RequestMapping' core/src/main/kotlin`
- `docs/rules/backend.md` § 테스트

## 작업

M1 완료 기준을 검증하는 시나리오 테스트를 `core/src/test/kotlin/io/github/ticketing/e2e/`에 만든다. 모두 `IntegrationTestSupport`를 상속하고, 실제 HTTP 엔드포인트(MockMvc)와 Testcontainers PostgreSQL·Redis를 쓴다. PG는 기존 테스트 fake를 쓰고, 웹훅은 테스트가 서명을 계산해 실제 웹훅 엔드포인트로 보낸다.

공통 헬퍼(가입·로그인, 주최자의 공연 등록, 서명 웹훅 전송, 상태 대기)는 `e2e/support/`에 한 번만 만든다. 기존 테스트에 같은 헬퍼가 있으면 옮기지 말고 재사용한다.

### 1. `BookingFlowE2ETests`: 완료 기준의 주 흐름

주최자 가입·로그인 → 공연장, 공연, 회차 등록 → booking 사본 생성 대기 → 일반 사용자 가입·로그인 → 좌석 2매 예매(`PENDING`) → 결제 요청(202) → PG fake가 받은 요청 확인 → 승인 웹훅 → 예매 `CONFIRMED`, 내 예매 목록에 표시, Redis 스냅샷 `SOLD`, `sold_seat` 2행

### 2. `SeatHoldConcurrencyTests`

- **같은 좌석 동시 선점**: 회원 20명이 같은 좌석으로 `POST /bookings`를 동시에 보낸다(`Executors.newVirtualThreadPerTaskExecutor()` + `CountDownLatch`). 201은 정확히 1건, 409 `SEAT_ALREADY_HELD`는 19건이고 `PENDING` 예매는 1건이다. 이어서 그 예매가 확정되면 `sold_seat`는 1행이다.
- **다중 좌석 전부 성공 또는 전부 실패**: 좌석이 겹치는 요청({1,2}, {2,3}, {3,4} 등)을 동시에 보낸다. 성공한 예매끼리 좌석이 겹치지 않고, 실패한 요청의 좌석은 Redis에 하나도 남지 않는다(선점 키 기준).
- **DB 최종 방어선**: Redis 선점을 우회한 상황을 재현한다. 두 예매가 같은 좌석을 가진 채 둘 다 승인 웹훅을 받으면 하나만 `CONFIRMED`가 되고, 다른 하나는 `CANCELLED(SEAT_ALREADY_SOLD)` + 환불 요청이 된다. 응답과 로그에 500이 없어야 한다. 재현 방법 예: 첫 예매 확정 후 선점 키를 지우고 두 번째 예매를 만든다.

### 3. `PaymentFailurePathTests`

- **결제 실패**: 실패 웹훅 → `CANCELLED(PAYMENT_FAILED)`, 같은 좌석을 다른 사용자가 바로 선점할 수 있다.
- **웹훅 중복**: 같은 `eventId`로 승인 웹훅을 3번 보낸다. 예매는 `CONFIRMED` 1번, `BookingConfirmed` 1건, PG 환불 호출 0번이다.
- **웹훅 지연으로 인한 만료 후 결제**: 결제 요청 후 가변 `Clock`을 6분 앞당기고 승인 웹훅을 보낸다. 예매는 `EXPIRED`가 되고, PG fake에 환불 요청이 1번 들어오며, 좌석은 다시 선점할 수 있다. 환불 웹훅까지 보내면 결제는 `REFUNDED`가 된다.

### 4. 규칙

- 기다릴 때는 Awaitility를 쓰고 `Thread.sleep`은 쓰지 않는다. 시간은 가변 `Clock`으로 조작한다.
- 테스트마다 데이터가 섞이지 않게 고유한 회차를 만든다. 다른 테스트 클래스와 DB를 공유해도 깨지지 않아야 한다.
- 테스트가 main 코드의 버그를 드러내면 **main 코드를 고친다.** 테스트를 느슨하게 바꾸거나 `@Disabled`로 넘기지 않는다. 고친 내용은 summary에 적는다.

### 5. 로드맵

`docs/roadmap.md` M1-7의 동시성 테스트 두 항목과 "실패 경로 테스트"를 `[x]`로 바꾼다.

## Acceptance Criteria

```bash
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.e2e.*"
rg -q '^- \[x\] 동시성 테스트: 같은 좌석' docs/roadmap.md
rg -q '^- \[x\] 실패 경로 테스트' docs/roadmap.md
./scripts/agent/test/check-summary.sh
```

## 검증 절차

1. e2e AC를 먼저 통과시키고, 마지막에 전체 check를 실행한다.
2. `phases/4-m1-verify/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "생성/수정한 파일 경로와 핵심 결론을 2줄·200자 이내로"`
   - 해결할 수 없는 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 테스트를 지우거나 `@Disabled`로 넘기지 마라. 단언을 약하게 바꾸지도 마라(예: "정확히 1건"을 "1건 이상"으로). 이유: 이 step의 목적이 완료 기준 검증이다.
- 유스케이스나 리포지토리를 직접 호출해 HTTP 흐름을 건너뛰지 마라. 단, 2절의 "DB 최종 방어선" 재현 준비는 예외다. 이유: 완료 기준은 "API 테스트로 통과"다.
- main 코드를 고칠 때 모듈 경계 규칙(이벤트로만 통신, 스키마 간 조인 금지)을 깨지 마라.
- 검색은 `rg`/`fd`를 우선 쓴다. 이유: `.gitignore`를 존중해 `build/` 결과가 섞이지 않고 출력이 짧다. 사용 가능한 도구는 Bash, Read, Write, Edit뿐이다.
- 기존 테스트를 깨뜨리지 마라.
- git commit 하지 마라. 이유: execute.py가 AC 통과를 확인한 뒤 step 단위로 커밋한다.
