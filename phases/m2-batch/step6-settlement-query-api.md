# Step 6: settlement-query-api

- model: 기본 / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `core/src/main/resources/db/migration/V9__create_daily_settlement.sql` (step 5)
- `core/src/main/kotlin/io/github/ticketing/settlement/` (step 4·5의 포트·어댑터)
- 컨트롤러 형태 참고: `core/src/main/kotlin/io/github/ticketing/booking/adapter/provided/web/BookingController.kt` (`@PreAuthorize`, `authentication.name.toLong()`)
- 오류 응답: `core/src/main/kotlin/io/github/ticketing/ApiExceptionHandler.kt`, `DomainException.kt`
- 웹 통합 테스트 형태: `core/src/test/kotlin/io/github/ticketing/booking/adapter/provided/web/BookingApiIntegrationTests.kt`
- `docs/rules/backend.md § API`, `§ 보안`, `§ 헥사고날`

## 작업

주최자가 자기 공연의 일별 정산을 조회하는 API를 만든다.

### API

```
GET /api/v1/settlements?from=2026-11-01&to=2026-11-10
권한: ORGANIZER (@PreAuthorize("hasRole('ORGANIZER')"))
organizerId = authentication.name.toLong()   // 요청 파라미터로 받지 않는다

200 [
  { "settlementDate": "2026-11-10", "performanceId": 1,
    "saleCount": 3, "saleAmount": 300000, "refundCount": 1, "refundAmount": 100000,
    "netAmount": 200000, "feeRate": 0.1000, "feeAmount": 20000, "payoutAmount": 180000 }
]
정렬: settlementDate 오름차순, performanceId 오름차순
```

- `from`, `to` 필수, `from <= to`, 구간은 최대 31일(양 끝 포함). 어기면 400 `ProblemDetail`, `code=INVALID_SETTLEMENT_PERIOD`.
- 구간이 31일로 제한되어 결과 크기가 공연 수 × 31을 넘지 않으므로 커서 페이지네이션을 두지 않는다. 이 예외를 컨트롤러 KDoc 한 줄로 남긴다.
- 정산 행이 없으면 빈 배열.

### 시그니처

```
application.port.provided.SettlementQueryUseCase
    fun findDailySettlements(query: DailySettlementQuery): List<DailySettlementView>
application.port.required.SettlementQueryPort       // step 5의 포트에 메서드 추가
    fun findDailySettlements(organizerId: Long, from: LocalDate, to: LocalDate): List<DailySettlementView>
application.service.SettlementQueryService          // @Transactional(readOnly = true), 구간 검증
adapter.provided.web.SettlementController, SettlementDtos
adapter.required.jooq.*                             // jOOQ로 조회 DTO에 직접 매핑
```

- 구간 검증 규칙은 서비스(또는 도메인)에 두고, 예외는 기존 `DomainException` 체계로 던져 `ApiExceptionHandler`가 변환하게 한다. 형식 오류(날짜 파싱 실패)는 컨트롤러 경계에서 400.
- 응답 DTO는 `adapter.provided.web`에 두고 도메인 타입을 그대로 내보내지 않는다.

### 테스트

`core/src/test/kotlin/io/github/ticketing/settlement/adapter/provided/web/SettlementApiIntegrationTests.kt` (`IntegrationTestSupport` 상속, 정산 행은 테스트에서 직접 넣는다)

- 주최자는 자기 공연 행만 받는다(다른 주최자 행 섞어 넣고 확인)
- USER 역할은 403, 인증 없음은 401
- `from > to`, 32일 구간은 400 + `code`
- 정렬 순서

## Acceptance Criteria

```bash
rg -q '/api/v1/settlements' core/src/main/kotlin/io/github/ticketing/settlement/adapter/provided/web
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.settlement*" --tests "*HexagonalArchitectureTests"
./scripts/agent/test/check-summary.sh :core:ktlintCheck :core:detekt
```

## 검증 절차

1. `run-tests.sh :core:test --tests "*SettlementApiIntegrationTests"`를 먼저 통과시킨 뒤 AC 전체를 실행한다.

## 금지사항

- `organizerId`를 쿼리 파라미터나 경로로 받지 마라. 이유: 다른 주최자의 정산을 조회할 수 있게 된다. 토큰의 주체로만 정한다.
- 조회를 JPA로 작성하지 마라. 이유: 조회는 jOOQ 규칙.
- `SecurityConfig`의 `permitAll` 목록에 이 경로를 넣지 마라. 이유: 인증·인가가 필요한 API다.
- 정산을 요청 시점에 다시 계산하지 마라. 이유: 조회 API는 Job이 만든 `daily_settlement`만 읽는다.
- 기존 테스트를 깨뜨리지 마라.
