# realtime 작성 지침

- [WebFlux 공통 지침](backend.md#webflux)을 적용한다. PostgreSQL에 접근하지 않으며 JPA, JDBC, jOOQ 의존성을 추가하지 않는다.
- 컨트롤러와 서비스는 `suspend` 함수와 `Flow`로 작성한다. `Mono`/`Flux`는 프레임워크 경계에서 쓴다.
- `GlobalScope`를 쓰지 않는다. 구조화된 동시성을 지키고, `CancellationException`을 삼키지 않는다.
- SSE는 `Flow<ServerSentEvent<T>>`로 내보내고 주기적 heartbeat를 보낸다. 좌석 SSE는 Redis Stream ID로 재생하며, `Last-Event-ID` 헤더를 우선하고 없으면 `lastEventId` 쿼리를 사용한다. 해당 ID가 Stream에 남아 있지 않으면 스냅샷부터 보낸다. 순번 SSE는 연결 시 현재 상태를 보낸다. [SSE 이벤트 계약](../architecture/events.md#4-sse-이벤트-realtime--브라우저)
- 연결이 끊기면 연결에 속한 구독·리더 참조를 정리한다. 대기자는 마지막 신호와 유예 시간을 기준으로 정리한다. [ADR 0021](../adr/0021-realtime-queue-and-sse-contracts.md)
- `realtime`은 인스턴스가 여러 개 떠도 동작해야 한다. 인스턴스 메모리에만 있는 상태에 의존하지 않는다.

대기열 API, SSE payload 및 접근 계약은 [API 규약](../architecture/api-conventions.md#realtime)과 [이벤트 카탈로그](../architecture/events.md#4-sse-이벤트-realtime--브라우저)를 따른다.
