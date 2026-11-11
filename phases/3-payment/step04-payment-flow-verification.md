# Step 4: payment-flow-verification

## 목표와 참조

M1-5의 예매 연계·환불·재처리·경합을 검증하고 문서를 동기화한다. notification 구현, M1-7 전체 동시성/모듈 테스트, M2 대사는 별도 작업이다. 코딩·테스트 규칙은 `docs/rules/backend.md`를 따른다.

- step 2·3의 `core/src/main/kotlin/io/github/ticketing/payment/application/port/provided/`, `adapter/provided/` 및 관련 테스트. 해당 시나리오의 심볼만 찾는다.
- `core/src/test/kotlin/io/github/ticketing/booking/application/flow/BookingPaymentFlowTests.kt`: fixture·Clock만 필요한 범위에서 재사용한다.
- `core/src/test/kotlin/io/github/ticketing/IntegrationTestSupport.kt`, `TestcontainersConfiguration.kt`.
- `docs/design.md` § 6.4 결제 (이벤트 기반), § 10 외부 연동; `docs/roadmap.md` § M1-5. payment (헥사고날).
- `docs/adr/0018-payment-retry-boundaries.md` § 결정, § 검증.

## 통합 검증

`core/src/test/kotlin/io/github/ticketing/payment/flow/PaymentFlowTests.kt`에 전체 컨텍스트와 Testcontainers PostgreSQL/Redis 테스트를 만든다. PG 출력 포트만 @Primary fake로 대체한다. 실제 리스너·서비스·저장소·서명 검증·HTTP를 사용하고 booking에 결과 이벤트를 직접 발행해 payment를 우회하지 않는다.

fake는 요청·키를 기록하고 결과를 제어한다. 같은 키에는 같은 거래를 반환하고, 호출 순간 활성 트랜잭션이 없음을 단언한다. 웹훅은 테스트가 서명해 MockMvc로 보낸다. 병렬 테스트의 데이터·호출 기록을 격리한다.

| 시나리오 | 핵심 단언 |
|---|---|
| 예매 생성 → 결제 API → 승인 | payment SUCCEEDED, booking CONFIRMED, sold_seat, 성공 이벤트 1건 |
| 결제 거절·실패 웹훅 | PaymentFailed 1건, booking PAYMENT_FAILED 취소, 좌석 반환 |
| Unknown → 원 이벤트 재처리 → 승인 | 실패 발행은 미완료, 같은 키 재사용, 최종 성공 |
| 같은 웹훅 반복·병렬 전달 | 결과 이벤트와 예매 확정은 1건. 락 충돌 요청도 같은 ID 재전송 후 성공 |
| 결제 요청 동시 처리 | PG 호출이 여럿이어도 거래·로컬 결제는 1건. 실패 발행 재처리 후 수렴 |
| PG 응답 전 웹훅 도착 | 주문으로 매칭·거래 ID 부착. 지연 Accepted가 상태를 되돌리지 않음 |
| 확정 예매 취소 → 환불 완료 | booking CANCELLED, 고정 환불 키, payment REFUNDED, 환불 이벤트 1건 |
| Clock으로 만료 → 지연 승인 | booking EXPIRED, RefundRequested → PG 환불 → payment REFUNDED |
| 환불 Unknown/Rejected → 원 이벤트 재처리 | REFUND_REQUESTED·미완료 유지, 같은 키 재호출 후 REFUNDED |
| 환불 뒤 승인·환불 중복 | 추가 성공/환불 이벤트 없음, 최종 상태 유지 |

Awaitility로 비동기 완료를 기다리고 실제 5분 대신 Clock을 전진시킨다. 재처리는 Modulith 공식 재제출 API로 원 미완료 발행을 실행해 완료까지 확인한다. 새 이벤트 발행만으로 레지스트리 복구 검증을 대신하지 않는다. main에 주기적 재처리 스케줄러를 추가하지 않는다.

## 문서와 완료 기준

- `docs/design.md` § 6.4·§ 10을 실제 구현과 맞추고 M1-5 구현 전 설명을 검증 결과로 갱신한다.
- ADR 0018은 결정 본문을 바꾸지 않고 검증 수단을 실제 테스트 이름과 맞춘다. 새 설계 변경은 사용자와 먼저 논의한다.
- 검증이 모두 통과한 뒤에만 로드맵 M1-5 네 항목을 체크한다. M1-6·M1-7·M1-8은 변경하지 않는다.
- 보관된 notification 초안은 실행하지 않는다. M1-5 완료가 M1 전체 완료를 의미하지 않는다.

```bash
test -f core/src/test/kotlin/io/github/ticketing/payment/flow/PaymentFlowTests.kt
! rg -n 'import io\.github\.ticketing\.booking' core/src/main/kotlin/io/github/ticketing/payment
./scripts/agent/test/run-tests.sh
./scripts/agent/test/check-summary.sh
./scripts/agent/docs/check-docs.sh
rg -q '^- \[x\] PG 출력 포트와 가짜 PG 어댑터' docs/roadmap.md
rg -q '^- \[x\] 멱등 키 기반 결제 요청과 웹훅 중복 처리' docs/roadmap.md
rg -q '^- \[x\] 결제 결과 이벤트 발행' docs/roadmap.md
rg -q '^- \[x\] 환불, 만료 후 결제 성공 시 자동 환불' docs/roadmap.md
```

Modulith·ArchUnit 검사는 전체 테스트에 포함된다. 필터를 사용하지 않는다. 문서 수정은 체크 전에 끝내고 검사한다. 완료 체크 후 최종 AC가 실패하면 체크를 되돌리고 실패 그대로 보고한다.

AC 통과 후에만 `phases/3-payment/index.json`의 step 4 상태와 200자 이내 summary를 갱신한다. 실패는 error·error_message, 사용자 결정 필요는 blocked·blocked_reason이다. 최종 보고에는 변경·검증·남은 작업·실패 로그 경로를 적는다. 직접 커밋은 하지 않는다.
