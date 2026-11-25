# gateway 작성 지침

- [WebFlux 공통 지침](backend.md#webflux)을 적용한다. JPA, JDBC, jOOQ 의존성을 추가하지 않는다. 도메인 로직은 해당 기능의 소유 앱에 둔다.
- 필터는 Gateway의 Reactor 계약에 맞춰 작성한다. JWT·입장 토큰·SSE 티켓 검증, rate limit, 라우팅은 Gateway에서 처리한다. [ADR 0020](../adr/0020-gateway-verification-contracts.md)
- 결제 조회(`/api/v1/payments/**`)는 `payment-api` 라우트로 payment 앱에 전달하며 `core-api`보다 먼저 매칭한다. 설정 키·필터는 [API 규약 § Gateway 라우팅](../architecture/api-conventions.md#gateway-라우팅)을 따른다.
- 웹훅(`/api/v1/payments/webhooks` 하위)은 fake-pg가 payment 앱을 직접 호출하므로 Gateway로 라우팅하지 않는다. `RequestPathGuardFilter`가 라우트 선택과 JWT 검증보다 먼저 404로 막는다.
- 같은 필터가 모호한 경로를 모든 경로에서 400 `INVALID_PATH`로 거부한다. 판정 기준과 이유는 [API 규약 § 경로 검증](../architecture/api-conventions.md#경로-검증)을 따른다.
- 입장 토큰 검증 실패는 대기열로 돌려보낼 수 있는 구분된 오류 코드로 응답한다.
- SSE 티켓은 한 번만 소비한다. 재연결에는 새 티켓이 필요하다.
- 라우팅과 오류 응답 계약은 [API 규약](../architecture/api-conventions.md)을 따른다.
