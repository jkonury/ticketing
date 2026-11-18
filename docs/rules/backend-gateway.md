# gateway 작성 지침

- [WebFlux 공통 지침](backend.md#webflux)을 적용한다. JPA, JDBC, jOOQ 의존성을 추가하지 않는다. 도메인 로직은 해당 기능의 소유 앱에 둔다.
- 필터는 Gateway의 Reactor 계약에 맞춰 작성한다. JWT·입장 토큰·SSE 티켓 검증, rate limit, 라우팅은 Gateway에서 처리한다. [ADR 0020](../adr/0020-gateway-verification-contracts.md)
- 입장 토큰 검증 실패는 대기열로 돌려보낼 수 있는 구분된 오류 코드로 응답한다.

- SSE 티켓은 한 번만 소비한다. 재연결에는 새 티켓이 필요하다. 라우팅과 오류 응답 계약은 [API 규약](../architecture/api-conventions.md)을 따른다.
