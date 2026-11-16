# 0032. 내부 예열을 실제 예매 구간 트래픽 구성으로 혼합

## 상태

수락 (2026-11-16). [ADR 0031](0031-warmup-through-real-booking-handler.md) 이후에도 core 탈최적화가 203건으로 남아, 사용자가 예열 요청을 외부 흐름 비율에 맞추고 한 회차 안에서 섞어 보내도록 요청했다. ADR 0031의 보안 경계(서버 역할 JWT + 기동 시 비밀 + 전용 체인 + Gateway 차단)와 ADR 0029~0030의 격리 정책(음수 ID, 롤백, 전용 Redis 키·TTL·정리, 호출 상한)은 그대로 유지하며 허용 경로만 확장한다. 대기열·SSE(realtime)는 이번 범위에서 제외한다.

## 맥락: 탈최적화 귀속 분석

`build/loadtest/runs/20261116-140027-hot-seats/1/core.jfr`의 예매 구간 `jdk.Deoptimization` 203건을 스레드와 호출 스택으로 나눴다. 스택은 상위 64프레임에서 잘려(125건) 요청 진입점까지 이어지지 않으므로, 스레드 종류와 스택에 남은 프레임군으로 귀속한다.

| 스레드 | 건수 | 귀속 |
|---|---|---|
| `task-*`(비동기 리스너용 가상 스레드) | 77 (38%) | PG 송신 경로. `DefaultRestClient` exchange 25건, 트레이싱 `createSenderSpan` 19건, Micrometer 관측 시작·스코프, Jackson 요청 본문 직렬화, 소켓 버퍼 flush. Hibernate 프레임은 0건 |
| `tomcat-*`(요청 스레드) | 124 (61%) | Hibernate 쓰기 경로, Jackson 역직렬화·캐시, 이벤트 멀티캐스터 |
| `okhttp-*` | 2 | 관측 내보내기 |

요청 스레드의 Hibernate 프레임군은 `UpdateCoordinatorStandard` 17건, `InsertCoordinatorStandard` 6건, `DefaultMergeEventListener`·`DefaultFlushEntityEventListener` 각 3건이다. 이벤트 멀티캐스터는 `PaymentRequested` 발행(`RequestPaymentService.requestPayment`)과 `PersistentApplicationEventMulticaster$TransactionalEventListeners.filter`에서 나온다.

기존 예열이 실제 경로와 다른 점은 다음과 같다.

1. 예매 생성 승자는 IDENTITY insert 후 롤백한다. 그래서 insert는 예열되지만 **UPDATE(결제 요청의 예매 갱신, 결제 수락·승인 갱신)와 커밋 경로, 결제 insert는 한 번도 실행되지 않는다.** 구간의 Hibernate 탈최적화는 UPDATE 쪽이 insert의 약 3배다.
2. 좌석은 항상 1장이다. 실제 요청은 1~4장이고 좌석 컬렉션 insert·Redis 다중 키 선점이 장수에 따라 달라진다.
3. 결제 요청 `POST /bookings/{id}/payments`, 상세 조회 `GET /bookings/{id}`, 웹훅 처리, PG 송신(RestClient·관측·트레이싱·HttpURLConnection)이 예열에 없다. 38%가 PG 송신 경로이다.
4. 한계: 이 귀속은 프레임군 기준이며 요청 단위로 확정한 것이 아니다. 예: Jackson 역직렬화가 예매 본문인지 웹훅 본문인지는 스택이 잘려 구분하지 못했다.

실제 구간의 호출 비율(`gatling-summary.json`)은 예매 생성 1,000(승자 57·충돌 943), 결제 요청 57, 상세 조회 87이고, 승인된 결제마다 웹훅이 한 번 온다.

## 결정

### 요청 구성과 비율

한 회차(1,000건) 안에서 인덱스 규칙으로 섞어 보낸다. 요청은 동시에 최대 1,000개가 비행하므로 종류가 서로 끼어든다.

| 종류 | 회차당 | 실제 비율 대응 |
|---|---|---|
| 예매 생성 승자(201) | 50 | 승자 5.7% |
| 예매 생성 충돌(409) | 약 820 | 충돌 94.3% |
| 결제 요청 `POST /api/v1/bookings/-1/payments`(202) | 50 | 5.7% |
| 상세 조회 `GET /api/v1/bookings/-1`(200) | 약 80 | 8.7% |

예매 생성은 좌석 1~4장 템플릿(좌석 1부터 연속)을 섞는다. 결제 요청 1건마다 payment 모듈이 웹훅 처리와 PG 조회를 한 번 수행하므로 웹훅·PG 송신은 결제 요청과 같은 비율(50)로 섞인다.

### 경로별 격리

- **예매 생성**: 기존과 같다. 좌석 수만 1~4로 늘고 키·정리는 좌석별로 확장했다.
- **상세 조회**: `BookingQueryUseCase` `@Primary` 데코레이터가 예열 principal의 `-1` 조회만 격리 환경으로 보낸다. 음수 회차의 `schedule`·`schedule_seat` 행과 예매를 롤백 전용 `REQUIRES_NEW` 트랜잭션 안에서 만들고 실제 조회 유스케이스(jOOQ 조인)로 읽는다. 커밋하지 않으므로 다른 연결에서 보이지 않는다.
- **결제 요청**: `RequestPaymentUseCase` 데코레이터가 같은 방식으로 롤백 트랜잭션 안에서 만든 예매에 실제 결제 요청 유스케이스(예매 UPDATE, `PaymentRequested` 발행)를 실행한다. 발행된 이벤트는 Modulith 레지스트리 행과 함께 롤백되고 AFTER_COMMIT 리스너(`PaymentRequestListener`)는 커밋이 없어 실행되지 않는다. 따라서 실제 결제·PG 요청이 생기지 않는다. 테스트가 `payment.payment`·`payment.webhook_event`·`event_publication`·`booking.*` 행 수 불변과 `TestPg` 요청 0건을 단언한다.
- **웹훅·PG 송신(payment 모듈)**: 롤백이 끝난 뒤 booking 데코레이터가 `PaymentWarmupRequested` 이벤트를 발행하고(모듈 간 호출은 이벤트로만 한다. booking→payment 의존은 이미 `PaymentRequested`로 있다) payment 모듈의 `@EventListener`가 다음을 수행한다.
  1. 롤백 전용 트랜잭션 안에서 실제 `PaymentTransactionService`로 결제 준비(insert)·PG 거래 수락(update)을 하고, 실제 `PaymentWebhookController.receive`에 올바르게 서명한 `PAYMENT_APPROVED` 웹훅을 넣는다. 서명 검증·본문 파싱·`recordWebhookEvent`·승인 UPDATE·`PaymentSucceeded` 발행이 모두 실제 코드로 실행되고 트랜잭션과 함께 롤백된다. 결제 도메인은 ID가 양수여야 하므로 음수 대신 실제 주문 ID(IDENTITY)가 닿을 수 없는 2^62 이상 구간을 쓰고, 롤백 전용이라 다른 트랜잭션에는 보이지 않는다.
  2. 트랜잭션 밖에서 같은 `FakePgAdapter`의 `findTransactions(LocalDate.EPOCH)`(`GET /v1/transactions`)를 호출한다. 같은 RestClient·요청 팩토리·관측·트레이싱 스택을 타며 PG에는 결제·환불 요청을 보내지 않는다. 상태를 바꾸지 않는 조회라 실제 PG에서도 성립한다. fake-pg는 변경하지 않았다.
  3. 상한 5,000회, 예외는 삼켜 경고만 남겨 요청자에게 전파하지 않는다.
- 예열 외 요청의 동작은 데코레이터가 `@Primary`로 위임하므로 변하지 않는다. 예열을 켜지 않으면 데코레이터와 payment 리스너 빈은 생성되지 않는다.

### 보안

ADR 0031의 5중 경계를 그대로 쓰고 전용 체인의 허용 경로만 고정 템플릿 세 개로 늘렸다. 그 밖은 `denyAll`이다.

- `POST /api/v1/bookings`
- `POST /api/v1/bookings/-1/payments`
- `GET /api/v1/bookings/-1`

예열 principal이라도 예매 ID가 `-1`이 아니거나 예매 생성 본문이 템플릿(회차 1, 좌석 `[1..n]`, n은 1~4, 두 멱등 키 중 하나)이 아니면 실제 유스케이스에 닿지 못한다. 취소·내 예매 목록·웹훅 경로·임의 경로는 403이다. 예열 principal에는 payment 모듈 경로가 없으며 payment 예열은 JVM 내부 이벤트로만 시작한다.

## 검토한 대안

- 예열 요청 중 PG 송신에 실제 `POST /v1/payments`(음수 주문)를 보내기: 실제 PG에서는 가짜 결제가 생길 수 있어 운영에서 성립하지 않는다. 기각.
- PG에 일부러 검증 실패하는 POST 보내기: POST 본문 직렬화는 예열되지만 운영 PG에 연속 오류 요청이 간다. 이번에는 채택하지 않았다. **POST 본문 쓰기와 `HttpURLConnection` POST 출력 경로는 예열되지 않는 한계**다.
- fake-pg에 예열 전용 엔드포인트 추가: 측정 대상(PG 송신 경로)이 운영과 달라진다. 기각.
- payment 모듈을 booking에서 직접 호출: 모듈 경계 규칙 위반. 이벤트로 대체.
- 외부 기동 스레드로 payment를 따로 먼저 예열: 요청이 한 회차에 섞이지 않는다. 사용자 합의(섞기)에 어긋나 기각.

## 한계

- 예열 이벤트(`PaymentWarmupRequested`)는 payment 모듈의 공개 이벤트 타입으로 추가됐다. JVM 내부에서만 발행할 수 있고 데이터를 담지 않는다.
- payment 쓰기는 롤백되지만 PostgreSQL 시퀀스(IDENTITY)는 증가한다. 롤백한 예매·결제 수만큼 값 공백이 생긴다. 예매 생성 예열이 이전부터 같은 특성이었다.
- 예열이 커진 만큼 Gateway 예열 시간이 약 25초에서 28초대로 늘었다(readiness 전, 120초 상한 안).
- 탈최적화 감소와 지연 개선의 인과는 결과 보고에 측정한 만큼만 기록한다.

## 결과와 검증

[결과 보고](../performance/m5/18-warmup-traffic-mix.md)에 기록한다.
