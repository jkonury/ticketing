# Step 1: core-revoke-payment-read

- model: 기본 / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `core/src/main/kotlin/io/github/ticketing/member/service/AuthService.kt`, `member/domain/RefreshToken.kt`, `member/repository/RefreshTokenRepository.kt`, `member/web/AuthController.kt`
- `core/src/main/kotlin/io/github/ticketing/member/security/SecurityConfig.kt` (공개 경로, 역할 규칙)
- `core/src/main/kotlin/io/github/ticketing/payment/` 아래 `application/port/provided/PaymentUseCases.kt`, `adapter/required/jooq/ReconciliationStore.kt`(jOOQ 조회 방식 참고), `adapter/provided/web/PaymentWebhookController.kt`
- `core/src/main/kotlin/io/github/ticketing/booking/adapter/provided/web/BookingController.kt` (인증 회원 ID를 얻는 방식 참고)
- `core/src/test/kotlin/io/github/ticketing/member/MemberAuthIntegrationTests.kt`, `core/src/test/kotlin/io/github/ticketing/IntegrationTestSupport.kt`
- `docs/rules/backend.md § 헥사고날`, `§ 영속성`, `§ API`, `§ 보안`, `§ 테스트`

## 작업

M4 BFF가 쓸 API 두 개를 추가한다. 두 기능은 서로 무관하며 각각 자기 모듈 안에서 끝난다.

### 1. member: refresh 토큰 폐기 `POST /api/v1/auth/tokens/revoke`

```kotlin
// AuthService
fun revoke(rawRefreshToken: String)
```

- 본문 `{"refreshToken": "..."}`(빈 문자열 불가, 기존 refresh 요청 DTO와 같은 검증). 응답은 항상 `204 No Content`.
- 해시로 찾은 토큰이 있고 아직 폐기되지 않았으면 그 토큰만 폐기한다(`Clock` 기준 시각). 없거나 이미 폐기됐으면 아무것도 하지 않는다(멱등).
- 공개 경로다. `SecurityConfig`의 기존 공개 경로(`/auth/tokens`, `/auth/tokens/refresh`)와 같은 방식으로 허용한다. 이유: access 토큰이 만료된 뒤에도 로그아웃할 수 있어야 한다.

### 2. payment: 결제 상태 조회 `GET /api/v1/payments/me?bookingIds=1,2`

헥사고날 구조(`docs/rules/backend.md § 헥사고날`)를 따른다.

```kotlin
// application/port/provided
data class PaymentStatusView(val bookingId: Long, val status: String, val failureReason: String?, val approvedAt: Instant?, val refundedAt: Instant?)
interface PaymentQueryUseCase { fun findMine(memberId: Long, bookingIds: List<Long>): List<PaymentStatusView> }
// application/port/required: 조회 포트, adapter/required/jooq: jOOQ 구현
// adapter/provided/web: PaymentQueryController
```

- `payment.payment`의 `order_id`가 예매 ID다. `member_id = 인증 회원 ID AND order_id IN (...)`만 반환한다. 다른 회원의 예매 ID는 결과에서 빠질 뿐 오류가 아니다. 결제가 없는 예매도 빠진다.
- `bookingIds`는 1~50개. 비었거나 50개를 넘으면 400 `INVALID_REQUEST`(기존 `ApiExceptionHandler`의 검증 실패 형식). 숫자가 아닌 값도 400.
- USER 역할만 허용한다(예매 API와 같은 규칙). ORGANIZER는 403 `FORBIDDEN`.
- 응답은 배열이며 `bookingId` 오름차순.

### 3. 테스트

- `MemberAuthIntegrationTests`에 추가(또는 같은 패키지 새 클래스): 폐기한 refresh 토큰으로 갱신하면 401 `INVALID_REFRESH_TOKEN`; 모르는 토큰 폐기는 204; 같은 토큰 두 번 폐기도 204; access 토큰 없이 호출해도 204.
- `core/src/test/kotlin/io/github/ticketing/payment/adapter/provided/web/PaymentQueryApiIntegrationTests.kt`(새 파일, `IntegrationTestSupport` 상속): 본인 결제만 반환, 다른 회원 예매 ID는 제외, 51개면 400, 토큰 없으면 401, ORGANIZER면 403. 결제 데이터는 기존 테스트가 쓰는 방식(저장 포트 또는 jOOQ insert)으로 준비한다.

## Acceptance Criteria

```bash
rg -q 'tokens/revoke' core/src/main/kotlin/io/github/ticketing/member
rg -q 'payments/me' core/src/main/kotlin/io/github/ticketing/payment
./scripts/agent/test/run-tests.sh :core:test --tests "*MemberAuthIntegrationTests" --tests "*PaymentQueryApiIntegrationTests" --tests "*ModularityTests"
./scripts/agent/test/check-summary.sh :core:ktlintCheck :core:detekt
```

## 검증 절차

1. 새 테스트부터 통과시킨 뒤 위 AC 전체를 실행한다.
2. 마지막에 `./scripts/agent/test/run-tests.sh :core:test`로 코어 전체 회귀를 확인한다.

## 금지사항

- 폐기 API에서 회원의 모든 refresh 토큰을 폐기하지 마라. 이유: 로그아웃은 해당 세션만 끝낸다. 전체 폐기는 재사용 감지에만 쓴다.
- 폐기 API가 토큰 존재 여부에 따라 다른 응답을 주게 하지 마라. 이유: 토큰 유효성 탐색 수단이 된다.
- payment 조회에서 `booking` 스키마나 모듈을 참조하지 마라. 이유: 스키마 간 조인 금지, 모듈 간 직접 참조 금지(AGENTS.md 4절).
- 토큰 값을 로그에 남기지 마라. 이유: `docs/rules/backend.md § 보안`.
- 기존 테스트를 지우거나 비활성화하지 마라.
