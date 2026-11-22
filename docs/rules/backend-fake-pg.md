# fake-pg 작성 지침

WebMVC와 [Virtual Thread 공통 지침](backend.md#virtual-thread)을 적용한다.

- 결제 흐름 검증에 필요한 지연·타임아웃·실패·중복 웹훅을 설정으로 재현한다. 결제 대사에 필요한 거래 내역 조회 계약을 유지한다. [ADR 0012](../adr/0012-fake-pg.md)

웹훅은 payment 앱(`http://localhost:18085/api/v1/payments/webhooks/fake-pg`)을 직접 호출하며 Gateway와 core를 거치지 않는다. 주소는 `FAKE_PG_WEBHOOK_URL`로, 지연은 `FAKE_PG_WEBHOOK_DELAY`(기본 500ms)로 바꾼다.

테스트 제어: 시나리오를 지정할 수 없는 호출자(payment 앱) 뒤에서 "다른 상태를 만든 뒤 승인이 도착한다"는 순서를 고정 지연 없이 만들려면 주문 단위 승인 보류를 쓴다. 제어 API(`/v1/test/approval-holds`)는 `fake-pg.test-controls.enabled=true`일 때만 등록되며 기본은 `false`다. `scripts/e2e/run.sh`만 이 속성을 켠다(dev·부하 실행에서는 경로가 404다). 결제 요청 전에 `POST /v1/test/approval-holds/{orderId}`로 등록하면(새 등록 201, 이미 등록·보류 중이면 현재 상태 200, 등록이 1,000개에 이르면 429) 그 주문의 첫 승인(SUCCESS)이 보류되고, `GET`이 `WAITING`(등록만)·`HELD`(거래 보류됨)를 알려주며, `POST .../release`가 보류된 거래의 승인과 웹훅을 즉시 한 번 보낸다(보류된 거래가 없으면 409). 주문마다 상태 하나를 락 안에서 전이하므로 보류 중 재등록이나 같은 주문의 다른 멱등 키 거래가 기존 보류를 덮어쓰지 않고, 해제하면 등록도 남지 않는다. 환불 웹훅에는 영향이 없다.

웹훅과 거래 내역 API의 상세 계약은 [연동 규약](../architecture/integrations.md)을 따른다.
