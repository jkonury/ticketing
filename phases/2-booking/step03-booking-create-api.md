# Step 3: booking-create-api

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- step 0~2에서 만든 `core/src/main/kotlin/io/github/ticketing/booking/` 전체
- `core/src/main/kotlin/io/github/ticketing/DomainException.kt`, `ApiExceptionHandler.kt`
- `core/src/test/kotlin/io/github/ticketing/member/MemberAuthIntegrationTests.kt` (토큰을 얻는 테스트 형식)
- `docs/rules/backend.md` § API, § 보안, § 트랜잭션과 동시성

## 작업

### 1. 예매 생성 `POST /api/v1/bookings` (`hasRole('USER')`)

- 헤더 `Idempotency-Key`(필수, 1~100자), 본문 `{scheduleId, seatIds: [..]}` (1~4개)
- 응답 201 `{bookingId, status: "PENDING", totalAmount, expiresAt, seats: [{seatId, label, price}]}`
- 유스케이스 `CreateBookingUseCase.create(CreateBookingCommand): BookingResult` 흐름:
  1. `(memberId, idempotencyKey)`로 기존 예매가 있으면 그대로 반환한다(200). 동시 요청이 유니크 제약에 걸리면 기존 예매를 다시 조회해 반환한다.
  2. 회차 사본에서 좌석을 조회한다. 없는 좌석이 있으면 404 `SEAT_NOT_FOUND`(회차 사본이 없으면 `SCHEDULE_NOT_FOUND`)
  3. `HoldToken`(UUID)을 만들어 `SeatHoldPort.hold`를 호출한다. `Unavailable`이면 409 `SEAT_ALREADY_HELD`이고, 응답에 좌석 ID 목록을 넣는다.
  4. DB에 `PENDING`을 저장한다(짧은 트랜잭션). **저장이 실패하면 선점을 해제하고** 예외를 다시 던진다.
- 트랜잭션 경계: Redis 선점은 DB 트랜잭션 밖에서 한다. 저장만 `@Transactional` 메서드나 `TransactionTemplate`으로 감싼다.
- 도메인 예외(`BookingRuleException`)는 `application`에서 `DomainException` 하위 예외로 바꾼다. 예: `TOO_MANY_SEATS` → 400, `NOT_BOOKING_OWNER` → 403, `INVALID_BOOKING_STATE` → 409. 변환은 한 곳에서 한다.

### 2. 조회 (jOOQ QueryPort, `booking.adapter.required.jooq`)

- `GET /api/v1/bookings/me?cursor=&size=`: 내 예매 목록. ID 내림차순 커서이고 `size` 기본 20·최대 50. 항목 `{bookingId, status, performanceTitle, startsAt, seatCount, totalAmount, createdAt}`. `booking` 스키마 안의 조인(`booking` + `schedule` + `booking_seat`)만 쓴다.
- `GET /api/v1/bookings/{id}`: 상세(좌석 label 포함). 본인 예매가 아니면 404로 감춘다. 소유자 확인은 서비스에서 한다.
- 조회 응답의 상태는 저장된 값 그대로이고 `expiresAt`도 함께 내려준다. 조회 중에 상태를 쓰지 않는다(`readOnly`).

### 3. 대기 중 예매 취소 `POST /api/v1/bookings/{id}/cancel`

- 이번 step은 `PENDING` 취소만 다룬다. `CANCELLED(USER_REQUESTED)`로 저장한 뒤 **커밋 후** `SeatHoldPort.release`를 호출한다.
- `CONFIRMED` 취소(환불)는 step 4에서 같은 엔드포인트에 추가한다. 지금은 `CONFIRMED`이면 409 `INVALID_BOOKING_STATE`를 반환한다.
- 이미 `CANCELLED`인 예매를 다시 취소하면 같은 결과를 반환한다(멱등).

### 4. 테스트

- `core/src/test/kotlin/io/github/ticketing/booking/adapter/provided/web/BookingApiIntegrationTests.kt` (MockMvc, 가입·로그인으로 토큰 획득, 회차 사본은 `ScheduleRegistered` 발행이나 유스케이스로 준비)
  - 생성 성공과 Redis 선점 확인, 멱등 키 재요청 시 같은 bookingId, 5매 400, 다른 사용자가 같은 좌석을 요청하면 409, 주최자 역할 403
  - 저장 실패 시 선점 해제: `BookingCommandPort`의 실패하는 fake를 넣은 유스케이스 단위 테스트로 검증한다.
  - 내 예매 목록 커서, 남의 예매 상세 404, PENDING 취소 후 같은 좌석 재선점 가능
- 유스케이스 단위 테스트는 출력 포트를 fake로 대체한다(mock 남용 금지).

### 5. 로드맵

`docs/roadmap.md` M1-3의 "내 예매 내역 조회 (jOOQ)"를 `[x]`로 바꾼다. "예매 생성(`PENDING`) → 결제 요청 이벤트 발행"과 "사용자 취소"는 step 4에서 체크한다.

## Acceptance Criteria

```bash
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.booking.adapter.provided.web.*"
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.booking.*" --tests "io.github.ticketing.HexagonalArchitectureTests"
rg -q '^- \[x\] 내 예매 내역 조회' docs/roadmap.md
```

## 검증 절차

1. 첫 AC로 대상 테스트를 수정한 뒤 booking 전체 AC를 실행한다.
2. `phases/2-booking/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "생성/수정한 파일 경로와 핵심 결론을 2줄·200자 이내로"`
   - 해결할 수 없는 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 컨트롤러가 서비스 구현이나 출력 포트를 직접 호출하지 마라. `port.provided`만 호출한다. 이유: 헥사고날 규칙
- 응답에 도메인 타입이나 JPA 엔티티를 그대로 내보내지 마라. DTO는 `adapter.provided.web`에 둔다.
- 조회를 JPA로 구현하지 마라. 이유: 조회는 jOOQ(ADR 0005)
- 결제 요청 API와 이벤트를 만들지 마라. 이유: step 4의 범위다.
- 대기열이나 입장 토큰 검증을 넣지 마라. 이유: 코어는 대기열을 모른다(설계 규칙 4).
- 검색은 `rg`/`fd`를 우선 쓴다. 이유: `.gitignore`를 존중해 `build/` 결과가 섞이지 않고 출력이 짧다. 사용 가능한 도구는 Bash, Read, Write, Edit뿐이다.
- 기존 테스트를 깨뜨리지 마라.
- git commit 하지 마라. 이유: execute.py가 AC 통과를 확인한 뒤 step 단위로 커밋한다.
