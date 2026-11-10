# Step 2: notification-module-tests

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `core/src/main/kotlin/io/github/ticketing/booking/Booking*.kt` (`BookingConfirmed`, `BookingCancelled`, `BookingExpired`)
- `core/src/main/kotlin/io/github/ticketing/payment/*.kt` (결제 계약 이벤트)
- `core/src/main/java/io/github/ticketing/notification/package-info.java`
- `core/src/test/kotlin/io/github/ticketing/TestcontainersConfiguration.kt`
- `docs/rules/backend.md` § 테스트, § 로깅

## 작업

### 1. notification 모듈 (계층형, `io.github.ticketing.notification`)

- `notification.service.BookingNotificationListener`: `@ApplicationModuleListener`로 `BookingConfirmed`, `BookingCancelled`, `BookingExpired`를 받아 SLF4J로 로그를 남긴다. 예: `log.info("예매 확정 알림 bookingId={} memberId={}", ...)`
- 실제 발송은 의도적으로 미룬 항목이다. 발송 채널을 추상화하는 포트를 만들지 않는다.
- 같은 이벤트가 다시 와도 부작용이 없다(로그뿐).

### 2. 모듈 단위 테스트 (`@ApplicationModuleTest`)

| 클래스 | 위치 | 검증 |
|---|---|---|
| `NotificationModuleTests` | `core/src/test/kotlin/io/github/ticketing/notification/` | `Scenario.publish(BookingConfirmed(...))` 후 리스너 처리 완료. 로그는 `OutputCaptureExtension`으로 확인 |
| `PaymentModuleTests` | `core/src/test/kotlin/io/github/ticketing/payment/` | `PaymentRequested`를 발행하면 PG fake가 호출되고, 승인 웹훅 처리 유스케이스 호출 후 `PaymentSucceeded`가 발행된다(`Scenario` … `andWaitForEventOfType`) |
| `BookingModuleTests` | `core/src/test/kotlin/io/github/ticketing/booking/` | 예매 생성 유스케이스 → 결제 요청 → `PaymentRequested` 발행. `PaymentSucceeded` 발행 → `BookingConfirmed` 발행 |

- 모듈 테스트는 해당 모듈 빈만 띄운다. 필요한 인프라(PostgreSQL, Redis)는 `@Import(TestcontainersConfiguration::class)`로 붙인다. 다른 모듈의 빈이 필요하면 테스트 안의 fake나 `@MockitoBean`으로 채우되 최소한으로 한다.
- `BootstrapMode`는 기본값(`STANDALONE`)부터 시도한다. 의존 모듈 빈이 꼭 필요할 때만 `DIRECT_DEPENDENCIES`를 쓰고, 그 이유를 테스트 클래스 주석에 적는다.
- 웹 계층(SecurityConfig 등 member 빈)에 의존하지 않도록 유스케이스(`port.provided`)를 직접 호출한다.
- 테스트 이름은 한국어 백틱, 시간은 고정 `Clock`을 쓴다.

### 3. 로드맵

`docs/roadmap.md` M1-6의 "예매 확정·취소 이벤트 수신 후 로그 출력"과 M1-7의 "모듈 단위 테스트(`@ApplicationModuleTest`)"를 `[x]`로 바꾼다.

## Acceptance Criteria

```bash
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.notification.*" --tests "*ModuleTests"
rg -q '^- \[x\] 예매 확정·취소 이벤트 수신 후 로그 출력' docs/roadmap.md
rg -q '^- \[x\] 모듈 단위 테스트' docs/roadmap.md
./scripts/agent/test/check-summary.sh
```

## 검증 절차

1. 대상 테스트 AC를 먼저 통과시키고, 마지막에 전체 check를 실행한다.
2. `phases/3-payment/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "생성/수정한 파일 경로와 핵심 결론을 2줄·200자 이내로"`
   - 해결할 수 없는 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- notification에서 booking·payment 내부 패키지(`domain`, `application`, `adapter`)를 import하지 마라. 루트 이벤트 타입만 쓴다. 이유: 설계 규칙 1
- 이메일·SMS 발송 코드나 외부 의존성을 추가하지 마라. 이유: 실제 알림 발송은 의도적으로 미룬 항목이다.
- 모듈 테스트를 통과시키려고 main 코드의 모듈 의존을 넓히지 마라(`allowedDependencies` 완화 등). 이유: 경계 검증의 의미가 사라진다.
- 검색은 `rg`/`fd`를 우선 쓴다. 이유: `.gitignore`를 존중해 `build/` 결과가 섞이지 않고 출력이 짧다. 사용 가능한 도구는 Bash, Read, Write, Edit뿐이다.
- 기존 테스트를 깨뜨리지 마라.
- git commit 하지 마라. 이유: execute.py가 AC 통과를 확인한 뒤 step 단위로 커밋한다.
