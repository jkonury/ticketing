# Step 3: realtime-last-event-id-query

- model: 기본 / reasoning_effort: low

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `realtime/src/main/kotlin/io/github/ticketing/realtime/seat/SeatStatusStreamController.kt`
- `realtime/src/test/kotlin/io/github/ticketing/realtime/seat/SeatStatusStreamTests.kt`
- `gateway/src/main/kotlin/io/github/ticketing/gateway/sse/SseTicketFilter.kt` (쿼리 전달 확인용, 수정 대상 아님)
- `docs/rules/backend.md § WebFlux + Coroutines`, `§ 테스트`

## 작업

브라우저는 1회용 SSE 티켓 때문에 `EventSource` 자동 재연결을 쓰지 못하고, 새 티켓으로 다시 연결할 때 `Last-Event-ID` 헤더를 실을 수 없다. 좌석 스트림이 쿼리로도 마지막 이벤트 ID를 받게 한다(ADR 0022 결정 5).

### 1. `GET /api/v1/streams/schedules/{scheduleId}/seats`

- 선택 쿼리 파라미터 `lastEventId`를 추가한다.
- 유효 값 = `Last-Event-ID` 헤더가 비어 있지 않으면 헤더, 아니면 쿼리. 이후 처리(형식 검사, Stream 재생, 스냅샷 대체)는 기존 로직을 그대로 쓴다.

### 2. Gateway 전달 확인

`SseTicketFilter`가 `ticket`만 지우고 다른 쿼리를 보존하는지 코드로 확인한다. 보존하지 않으면 고치지 말고 결과 보고에 적는다.

### 3. 테스트: `SeatStatusStreamTests`에 추가

- 쿼리 `lastEventId`로 연결하면 그 다음 변경부터 재생된다(스냅샷 없이).
- 헤더와 쿼리가 둘 다 있으면 헤더가 우선한다.
- 쿼리 값이 잘못된 형식이면 스냅샷부터 보낸다.

## Acceptance Criteria

```bash
rg -q 'RequestParam\(.*lastEventId' realtime/src/main/kotlin/io/github/ticketing/realtime/seat/SeatStatusStreamController.kt
./scripts/agent/test/run-tests.sh :realtime:test --tests "*SeatStatusStreamTests"
./scripts/agent/test/check-summary.sh :realtime:ktlintCheck :realtime:detekt
```

## 검증 절차

1. 위 AC를 실행한다. 마지막에 `./scripts/agent/test/run-tests.sh :realtime:test`로 회귀를 확인한다.

## 금지사항

- 대기열 순번 스트림에는 추가하지 마라. 이유: 순번 스트림은 이벤트 ID를 쓰지 않는다(ADR 0021).
- Gateway 설정·코드를 바꾸지 마라. 이유: 이 step의 범위는 realtime이다. 문제가 있으면 보고한다.
- 기존 헤더 동작을 바꾸지 마라. 이유: e2e `MultiInstanceE2eTests`가 헤더 재생을 검증한다.
