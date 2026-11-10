# Step 0: fake-pg-api

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `fake-pg/build.gradle.kts`, `fake-pg/src/main/kotlin/io/github/ticketing/fakepg/FakePgApplication.kt`, `fake-pg/src/main/resources/application.yml`
- `docs/rules/backend.md` § Virtual Thread, § 보안, § 테스트
- `docs/adr/0012-fake-pg.md` § 결정

## 작업

가짜 PG 서버를 만든다. 별도 배포 단위이고 DB 없이 메모리에 거래를 보관한다. 코어의 `payment` 모듈(다음 phase)이 아래 **프로토콜**대로 호출하므로 경로, 필드, 헤더 이름을 바꾸지 마라.

### 1. 프로토콜

**결제 요청** `POST /v1/payments`
- 헤더 `Idempotency-Key`(필수)
- 본문 `{ "orderId": "string", "amount": 12000, "scenario": "SUCCESS|FAIL|TIMEOUT|DELAYED|DUPLICATE_WEBHOOK" (선택) }`
- 응답 202 `{ "transactionId": "string", "status": "PENDING" }`
- 같은 `Idempotency-Key`로 다시 요청하면 같은 `transactionId`를 반환하고, 새 거래나 웹훅을 만들지 않는다. 같은 키에 본문이 다르면 422 `IDEMPOTENCY_KEY_REUSED`

**환불 요청** `POST /v1/payments/{transactionId}/refund`
- 헤더 `Idempotency-Key`(필수). 승인된 거래만 환불할 수 있고, 아니면 409를 반환한다.
- 응답 202. 이후 `REFUND_COMPLETED` 웹훅을 보낸다.

**거래 내역** `GET /v1/transactions?date=YYYY-MM-DD` (UTC 날짜, 결제 대사용)
- 응답 `[{transactionId, orderId, amount, status: PENDING|APPROVED|FAILED|REFUNDED, requestedAt, approvedAt, refundedAt}]`

**웹훅** fake-pg가 `fake-pg.webhook.url`로 `POST`를 보낸다.
- 본문 `{ "eventId": "uuid", "type": "PAYMENT_APPROVED|PAYMENT_FAILED|REFUND_COMPLETED", "transactionId", "orderId", "amount", "occurredAt": ISO-8601, "failureReason": string|null }`
- 헤더 `X-FakePg-Timestamp`(epoch 초), `X-FakePg-Signature` = hex(HMAC-SHA256(secret, `"{timestamp}.{rawBody}"`))
- **중복 웹훅은 같은 `eventId`로** 다시 보낸다. 수신 측은 `eventId`로 중복을 걸러낸다.
- 수신 측이 2xx가 아니거나 연결에 실패하면 `max-attempts`까지 재시도한다.

### 2. 시나리오와 설정

시나리오가 요청에 있으면 그것을 쓰고, 없으면 설정의 확률로 정한다.

| 시나리오 | 동작 |
|---|---|
| `SUCCESS` | `webhook.delay` 후 `PAYMENT_APPROVED` |
| `FAIL` | `webhook.delay` 후 `PAYMENT_FAILED` (`failureReason: "CARD_DECLINED"`) |
| `TIMEOUT` | 응답을 `behavior.timeout-delay`만큼 늦춘다. **결제는 정상 진행해** 승인 웹훅을 보낸다(클라이언트는 타임아웃이 나도 실제로는 승인된 상황) |
| `DELAYED` | `behavior.delayed-webhook-delay` 후 승인 웹훅 |
| `DUPLICATE_WEBHOOK` | 승인 웹훅을 `webhook.duplicate-count`번 더 보낸다(같은 eventId) |

```yaml
fake-pg:
  webhook:
    url: ${FAKE_PG_WEBHOOK_URL:http://localhost:8080/api/v1/payments/webhooks/fake-pg}
    secret: ${FAKE_PG_WEBHOOK_SECRET}
    delay: 500ms
    duplicate-count: 2
    max-attempts: 3
  behavior:
    failure-rate: 0.0
    timeout-rate: 0.0
    duplicate-rate: 0.0
    timeout-delay: 10s
    delayed-webhook-delay: 6m
server.port: 8090
```

- `@ConfigurationProperties`로 받고 확률은 0.0~1.0으로 검증한다. 난수 소스는 주입할 수 있게 해 테스트에서 고정한다.
- 시각은 주입된 `Clock`에서 얻는다. `spring.threads.virtual.enabled=true`
- 웹훅 지연 발송은 `TaskScheduler` 같은 스케줄러로 한다. 요청 스레드에서 `Thread.sleep`으로 기다리지 않는다. 예외는 TIMEOUT 시나리오의 응답 지연이다. 이 지연은 Virtual Thread 위에서 일어나므로 허용한다.

### 3. 패키지 (`io.github.ticketing.fakepg.payment`)

`web`(컨트롤러, DTO), `service`(거래 저장소, 시나리오 결정, 웹훅 발송), `config` 정도로 단순하게 나눈다. 거래 저장소는 `ConcurrentHashMap` 기반으로 하고, 멱등 키 처리는 원자적이어야 한다(`computeIfAbsent` 등).

### 4. 테스트 (`fake-pg/src/test/kotlin/io/github/ticketing/fakepg/payment/`)

- 웹훅 수신은 테스트 안의 가짜 수신기로 확인한다. 예: `MockRestServiceServer`나 로컬 HTTP 서버. 기다릴 때는 Awaitility를 쓰고 `Thread.sleep`은 쓰지 않는다. 의존성이 없으면 `fake-pg/build.gradle.kts`에 `testImplementation("org.awaitility:awaitility-kotlin")`(BOM 관리)을 추가한다.
- 검증 항목: 시나리오별 웹훅 종류와 횟수, 같은 eventId의 중복 발송, 서명 값(테스트에서 직접 HMAC을 계산해 비교), 멱등 키 재요청, 키 재사용 422, 환불 흐름, 거래 내역 날짜 필터, 수신 실패 시 재시도
- 테스트 설정의 `webhook.delay`는 0 또는 수십 ms로 둔다.

### 5. 로드맵

`docs/roadmap.md` M1-4의 "결제 요청 API, 웹훅 발송", "지연·타임아웃·실패·중복 웹훅을 설정으로 제어", "거래 내역 조회 API (결제 대사용)"를 `[x]`로 바꾼다.

## Acceptance Criteria

```bash
./scripts/agent/test/run-tests.sh :fake-pg:test --tests "io.github.ticketing.fakepg.payment.*"
rg -q '^- \[x\] 결제 요청 API, 웹훅 발송' docs/roadmap.md
rg -q '^- \[x\] 지연·타임아웃·실패·중복 웹훅을 설정으로 제어' docs/roadmap.md
rg -q '^- \[x\] 거래 내역 조회 API' docs/roadmap.md
./scripts/agent/test/check-summary.sh :fake-pg:check
```

## 검증 절차

1. 위 AC를 실행하고, 실패하면 원인을 수정한다.
2. `phases/1-fake-pg/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "생성/수정한 파일 경로와 핵심 결론을 2줄·200자 이내로"`
   - 해결할 수 없는 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `core` 모듈을 의존하거나 core 코드를 복사하지 마라. 이유: fake-pg는 외부 시스템을 흉내 내는 독립 배포 단위다.
- DB(JPA, JDBC)를 추가하지 마라. 이유: 메모리 저장으로 충분하고, 재시작하면 내역이 사라지는 것은 감수한다.
- 웹훅 secret의 기본값을 설정 파일에 넣지 마라. 테스트는 테스트 프로퍼티로 넣는다. 이유: 비밀 값은 저장소에 넣지 않는다.
- 위 1절의 경로, 필드, 헤더 이름을 바꾸지 마라. 이유: 다음 phase의 payment 어댑터가 이 계약에 맞춰 작성된다.
- `compose.yaml`이나 Dockerfile을 건드리지 마라. 이유: step 1의 범위다.
- 검색은 `rg`/`fd`를 우선 쓴다. 이유: `.gitignore`를 존중해 `build/` 결과가 섞이지 않고 출력이 짧다. 사용 가능한 도구는 Bash, Read, Write, Edit뿐이다.
- 기존 테스트를 깨뜨리지 마라.
- git commit 하지 마라. 이유: execute.py가 AC 통과를 확인한 뒤 step 단위로 커밋한다.
