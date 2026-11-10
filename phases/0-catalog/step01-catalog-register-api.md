# Step 1: catalog-register-api

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- step 0에서 만든 `core/src/main/kotlin/io/github/ticketing/catalog/` 전체와 `V4__create_catalog_tables.sql`
- `core/src/main/kotlin/io/github/ticketing/member/web/AuthController.kt` (컨트롤러 형식)
- `core/src/main/kotlin/io/github/ticketing/member/service/MemberExceptions.kt` (`DomainException` 상속 형식)
- `core/src/main/kotlin/io/github/ticketing/member/security/SecurityConfig.kt` (인가 규칙, `ROLE_ORGANIZER`)
- `core/src/test/kotlin/io/github/ticketing/member/MemberAuthIntegrationTests.kt` (가입·로그인으로 토큰을 얻는 테스트 형식)
- `docs/rules/backend.md` § 코어 모듈 구조, § API, § 보안

## 작업

주최자가 공연장, 공연, 회차를 등록하는 API를 만들고, 회차 등록 시 다른 모듈이 쓸 이벤트를 발행한다.

### 1. API (`catalog.web`, `catalog.service`)

모두 `hasRole('ORGANIZER')`이다. 주최자 ID는 JWT `sub`에서 얻는다.

| 메서드·경로 | 요청 | 응답 |
|---|---|---|
| `POST /api/v1/venues` | `{name, seats: [{section, row, number, grade}]}` | 201 `{id, seatCount}` |
| `POST /api/v1/performances` | `{title, description, venueId, prices: [{grade, price}]}` | 201 `{id}` |
| `POST /api/v1/performances/{id}/schedules` | `{startsAt}` | 201 `{id}` |

- 공연장은 주최자가 공유하는 자원으로 보고 소유자를 두지 않는다.
- 공연을 등록할 때 공연장 좌석에 쓰인 등급이 가격표에 모두 있어야 한다. 없으면 400 `PRICE_MISSING_FOR_GRADE`
- 회차는 해당 공연의 `organizer_id`와 요청자가 같을 때만 등록할 수 있다. 다르면 403 `NOT_PERFORMANCE_OWNER`. 확인은 서비스에서 한다.
- 오류 코드 예: `VENUE_NOT_FOUND`, `PERFORMANCE_NOT_FOUND`, `DUPLICATE_SCHEDULE`. 예외는 `DomainException`을 상속한다.
- 입력은 Bean Validation으로 검증한다. 좌석은 최소 1개, 최대 5000개다.
- `@Transactional`은 서비스에만 둔다.

### 2. 이벤트 `io.github.ticketing.catalog.ScheduleRegistered` (모듈 루트 = 공개 API)

```kotlin
data class ScheduleRegistered(
    val scheduleId: Long,
    val performanceId: Long,
    val performanceTitle: String,
    val organizerId: Long,
    val startsAt: Instant,
    val seats: List<ScheduleSeat>,
)
data class ScheduleSeat(val seatId: Long, val grade: String, val label: String, val price: BigDecimal)
```

- 파일: `catalog/ScheduleRegistered.kt`. `ScheduleSeat`도 같은 파일에 둔다.
- `booking`이 이 이벤트로 좌석·가격 사본을 만든다. 규칙 1 때문에 booking은 catalog를 직접 조회할 수 없다. 그래서 판매에 필요한 정보를 모두 담는다.
- `label`은 표시용 문자열이다(예: `"A구역 3열 5번"`).
- 회차 저장과 같은 트랜잭션에서 `ApplicationEventPublisher`로 발행한다. Modulith 이벤트 발행 레지스트리가 커밋 후 전달을 보장한다.
- 단순 타입만 쓴다(Long, String, Instant, BigDecimal, List). 이유: 추후 Kafka로 외부화한다.

### 3. 테스트 (`core/src/test/kotlin/io/github/ticketing/catalog/`)

- 통합 테스트(MockMvc): 주최자는 등록에 성공하고, 일반 사용자는 403, 비인증 요청은 401을 받는다. 가격 누락과 다른 주최자의 회차 등록 거부도 검증한다.
- 회차 등록 시 `ScheduleRegistered`가 좌석 수만큼의 `seats`를 담아 발행되는지 검증한다(`@RecordApplicationEvents` 또는 Modulith `PublishedEvents`).

### 4. 로드맵

`docs/roadmap.md` M1-2의 "주최자의 공연·회차 등록 API"를 `[x]`로 바꾼다.

## Acceptance Criteria

```bash
test -f core/src/main/kotlin/io/github/ticketing/catalog/ScheduleRegistered.kt
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.catalog.*" --tests "io.github.ticketing.ModularityTests"
rg -q '^- \[x\] 주최자의 공연·회차 등록 API' docs/roadmap.md
```

## 검증 절차

1. 위 AC를 실행하고, 실패하면 원인을 수정한다.
2. `phases/0-catalog/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "생성/수정한 파일 경로와 핵심 결론을 2줄·200자 이내로"`
   - 해결할 수 없는 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `member` 모듈의 클래스를 import하지 마라. 역할 문자열(`ORGANIZER`)은 `@PreAuthorize("hasRole('ORGANIZER')")`나 URL 규칙으로만 쓴다. 이유: 설계 규칙 1
- `ScheduleRegistered`에 JPA 엔티티나 enum 같은 catalog 내부 타입을 넣지 마라. 이유: 이벤트는 직렬화 가능한 단순 타입만 담는다.
- 이벤트 리스너를 만들지 마라. 이유: 수신은 booking phase의 범위다.
- 조회 API를 만들지 마라. 이유: step 2의 범위다.
- 검색은 `rg`/`fd`를 우선 쓴다. 이유: `.gitignore`를 존중해 `build/` 결과가 섞이지 않고 출력이 짧다. 사용 가능한 도구는 Bash, Read, Write, Edit뿐이다.
- 기존 테스트를 깨뜨리지 마라.
- git commit 하지 마라. 이유: execute.py가 AC 통과를 확인한 뒤 step 단위로 커밋한다.
