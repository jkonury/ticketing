# Step 2: fake-pg-kst-date

- model: haiku / reasoning_effort: low

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `fake-pg/src/main/kotlin/io/github/ticketing/fakepg/payment/service/TransactionStore.kt` (`findByRequestedDate`)
- `fake-pg/src/main/kotlin/io/github/ticketing/fakepg/payment/web/PaymentController.kt` (`GET /v1/transactions`)
- `fake-pg/src/test/kotlin/io/github/ticketing/fakepg/payment/TransactionHistoryTest.kt`, `FakePgTestSupport.kt`

## 작업

결제 대사(step 9)는 Asia/Seoul 일자 기준이다. fake-pg의 거래 조회 `GET /v1/transactions?date=YYYY-MM-DD`가 지금은 `requestedAt`을 UTC 날짜로 거른다. 이를 **Asia/Seoul 날짜**로 바꾼다.

- `TransactionStore.findByRequestedDate(date)`: `LocalDate.ofInstant(requestedAt, ZoneId.of("Asia/Seoul")) == date`. 영역 상수는 한 곳에 둔다.
- 정렬(`requestedAt`, `transactionId`)과 응답 형식(`TransactionResponse`)은 바꾸지 않는다.
- `TransactionHistoryTest`에 경계 테스트를 추가한다: UTC로는 전날이지만 KST로는 다음 날인 시각(예: `2026-11-10T15:30:00Z` = KST 11월 11일 00:30)에 요청한 거래는 `date=2026-11-11`로 조회되고 `date=2026-11-10`으로는 조회되지 않는다. 시각은 테스트 `Clock`으로 고정한다(기존 테스트가 시간을 다루는 방식을 따른다).
- 기존 테스트가 UTC 날짜를 전제로 했다면 KST 기준으로 고친다.

## Acceptance Criteria

```bash
rg -q 'Asia/Seoul' fake-pg/src/main/kotlin
! rg -q 'ZoneOffset.UTC' fake-pg/src/main/kotlin/io/github/ticketing/fakepg/payment/service/TransactionStore.kt
./scripts/agent/test/run-tests.sh :fake-pg:test
./scripts/agent/test/check-summary.sh :fake-pg:ktlintCheck :fake-pg:detekt
```

## 검증 절차

1. `./scripts/agent/test/run-tests.sh :fake-pg:test --tests "*TransactionHistoryTest"`로 먼저 확인한 뒤 AC를 실행한다.

## 금지사항

- 조회 API의 경로·파라미터 이름·응답 필드를 바꾸지 마라. 이유: step 9의 core PG 어댑터가 이 계약(`date`, `transactionId`, `orderId`, `amount`, `status`, `requestedAt`, `approvedAt`, `refundedAt`)을 그대로 쓴다.
- `Thread.sleep`으로 시각을 맞추지 마라. 이유: 테스트 규칙상 `Clock`을 고정한다.
- 기존 테스트를 깨뜨리지 마라.
