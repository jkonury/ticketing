# fake-pg 작성 지침

WebMVC와 [Virtual Thread 공통 지침](backend.md#virtual-thread)을 적용한다.

- 결제 흐름 검증에 필요한 지연·타임아웃·실패·중복 웹훅을 설정으로 재현한다. 결제 대사에 필요한 거래 내역 조회 계약을 유지한다. [ADR 0012](../adr/0012-fake-pg.md)

웹훅은 payment 앱(`http://localhost:18085/api/v1/payments/webhooks/fake-pg`)을 직접 호출하며 Gateway와 core를 거치지 않는다. 주소는 `FAKE_PG_WEBHOOK_URL`로, 지연은 `FAKE_PG_WEBHOOK_DELAY`(기본 500ms)로 바꾼다.

테스트 제어: 고정 지연 없이 순서를 만들려면 주문 단위 승인 보류를 쓴다. `fake-pg.test-controls.enabled=true`일 때만 등록되며 `scripts/e2e/run.sh`만 켠다. 계약은 [연동 규약 § 승인 보류 테스트 제어](../architecture/integrations.md#승인-보류-테스트-제어)를 따른다.

웹훅과 거래 내역 API의 상세 계약은 [연동 규약](../architecture/integrations.md)을 따른다.
