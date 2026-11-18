# fake-pg 작성 지침

WebMVC와 [Virtual Thread 공통 지침](backend.md#virtual-thread)을 적용한다.

- 결제 흐름 검증에 필요한 지연·타임아웃·실패·중복 웹훅을 설정으로 재현한다. 결제 대사에 필요한 거래 내역 조회 계약을 유지한다. [ADR 0012](../adr/0012-fake-pg.md)

웹훅과 거래 내역 API의 상세 계약은 [연동 규약](../architecture/integrations.md)을 따른다.
