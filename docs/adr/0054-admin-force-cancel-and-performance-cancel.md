# ADR 0054: 관리자 예매 강제 취소와 공연 취소 Saga

- 상태: 합의
- 작성일: 2026-11-28
- 합의일: 2026-11-28
- 결정 주체: 2026-11-28 관리자 기능 후속 설계 인터뷰(Q1~Q10)
- 대체: 없음
- 대체됨·일부 변경: 없음
- 관련: [ADR 0052](0052-admin-intervention-states.md), [ADR 0034](0034-platform-admin-role-and-audit.md), [ADR 0035](0035-coupon-module-reservation-saga.md), [ADR 0036](0036-settlement-list-price-platform-discount.md), [실행 계획](../workflow/plans/2026-11-28-admin-cancel-saga.md), [아키텍처](../architecture.md)

## 배경

ADR 0052는 판매 중지와 이용 정지 같은 약한 개입만 다뤘다. 예매 강제 취소와 공연 취소는 booking·payment·coupon·settlement를 가로질러 범위에서 뺐다. 운영자는 아직 문제 예매를 환불할 수 없고, 열리지 않을 공연의 예매를 일괄 정리할 수도 없다. 사용자 취소 경로(`CancelBookingService`)와 늦은 결제 성공의 자동 환불 경로(`PaymentResultService`)는 이미 있다. 공연 하나에 예매가 수천 건일 수 있어 한 트랜잭션으로 처리할 수 없다. 또 생성 검사를 통과한 예매가 취소 처리보다 늦게 커밋될 수 있어 이를 막을 최종 방어선이 필요하다.

## 결정

1. 관리자는 PENDING과 CONFIRMED 예매를 강제 취소할 수 있다. 처리는 사용자 취소와 같은 상태별 경로를 탄다. PENDING은 선점 해제와 쿠폰 복원, CONFIRMED는 `sold_seat` 삭제와 환불 요청이다. EXPIRED·CANCELLED는 409로 거부한다. 진행 중 결제가 늦게 성공하면 기존 자동 환불(`BOOKING_CANCELLED`)로 보상한다.
2. `CancelReason`에 `ADMIN_CANCELLED`와 `PERFORMANCE_CANCELLED`를 추가한다. Kafka 계약 `BookingCancelled`는 필드를 바꾸지 않고 `reason` 값만 늘린다.
3. 강제 취소는 사유(필수, 최대 200자)를 받는다. 사유는 감사 로그에만 남긴다. booking이 core 내부 이벤트로 관리자 취소 사실(행위자, 예매, 사유)을 발행하고 audit가 `admin_action`에 멱등으로 기록한다. `BookingCancelled`에는 행위자와 사유를 넣지 않는다.
4. 확정 예매를 운영자가 취소해도 쿠폰은 사용자 취소처럼 소멸한다(ADR 0036 결정 3). PENDING 예매의 쿠폰은 복원한다.
5. 공연에 종료 상태 `CANCELLED`를 추가한다. `ACTIVE`·`SUSPENDED`에서 전이하고 되돌릴 수 없다. 재개·재취소는 409로 거부한다. 사유는 필수다. 공개 노출·신규 예매·주최자 수정은 판매 중지와 같게 막고, 주최자 전용 상세와 정산 조회는 유지한다. 회차 단위 취소는 다루지 않는다.
6. catalog는 공연 취소를 이벤트로 발행한다. booking의 리스너 하나가 Saga를 진행한다. 먼저 `performance_sale`·`schedule` 사본에 취소를 반영해 신규 예매를 막는다. 반영에는 판매 중지와 같은 상태 번호 규칙을 쓴다. 그다음 해당 공연의 PENDING·CONFIRMED 예매를 청크마다 별도 트랜잭션에서 결정 1의 경로로 취소한다. 사유는 `PERFORMANCE_CANCELLED`다. 실패하면 이벤트 재발행으로 다시 실행하고, 이미 취소된 예매는 건너뛴다. 별도 Batch 잡이나 Saga 상태 테이블은 두지 않는다. audit도 같은 이벤트를 받아 기록한다.
7. booking 사본은 판매 중지와 공연 취소를 구분해 저장한다. 최종 방어선으로 결제 요청과 결제 확정 경로가 취소 여부를 검사한다. 취소된 공연의 결제 요청은 거부한다. 결제가 성공하면 예매를 `CANCELLED(PERFORMANCE_CANCELLED)`로 바꾸고 자동 환불한다. 판매 중지는 기존처럼 결제를 허용한다(ADR 0052 결정 1).
8. 관리자 예매 목록에 `performanceId` 필터를 추가해 Saga 진행을 확인한다. 관리자 예매 상세에는 취소 유형이 있을 때 `cancelReason`을 반환한다. 관리자가 입력한 사유 원문은 감사 로그에만 남긴다. 진행률 테이블은 두지 않는다. 남은 예매와 미완료 이벤트 확인 절차는 런북에 둔다.

## 검토한 대안

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| 공연 취소를 Spring Batch 잡으로 실행 | 실행 트리거·이력 관리가 추가된다. 이벤트 재발행과 멱등 청크로 재시작을 충분히 처리할 수 있다. |
| 예매마다 개별 취소 이벤트를 발행해 분산 처리 | 이벤트 수가 예매 수만큼 늘고 순서·관측이 복잡하다. 처리 주체가 booking 하나라 이점이 없다. |
| Saga 상태 테이블로 진행률 관리 | 관리자 예매 목록 필터로 남은 예매를 셀 수 있어 새 저장소가 필요 없다. |
| 운영자 취소 시 사용된 쿠폰 복원 | coupon에 `USED → AVAILABLE` 전이와 정산 할인 부담 규칙 변경이 필요하다. 후속으로 둔다. |
| `BookingCancelled`에 행위자·사유 추가 | 외부 소비자에게 필요 없는 정보이고 계약 변경 비용이 생긴다. 사유는 감사 로그에만 둔다(ADR 0052 결정 4). |
| 결제 확정 경로의 검사 없이 Saga만으로 정리 | 생성 검사 통과 후 늦게 커밋된 예매가 남아 취소된 공연의 예매가 확정될 수 있다. |

## 결과

- 얻는 것: 문제 예매의 환불 수단, 공연 취소의 일괄 정리, 취소 공연에서 확정이 생기지 않는다는 보장, 감사 추적.
- 감수하는 것: 운영자 취소에도 쿠폰 소멸, Saga 진행 중 일시적으로 남은 예매, 취소 반영 전 늦게 도착한 결제 요청의 자동 환불, 정산 일별 순액의 음수 가능성(기존 규칙).
- 바뀌는 문서·지침: [용어사전](../glossary.md)(반영함). 구현 단계에서 `docs/architecture.md`, [흐름](../architecture/flows.md), [이벤트 카탈로그](../architecture/events.md), API·ERD 문서, 런북을 같은 변경에서 고친다.

## 검증

[실행 계획](../workflow/plans/2026-11-28-admin-cancel-saga.md)의 task별 통합 테스트로 확인한다. 상태별 강제 취소, 공연 취소 Saga의 청크 처리와 재실행 멱등, 취소 공연의 결제 확정 시 자동 환불, `BookingCancelled` 골든 파일과 소비자 테스트, 감사 로그 멱등, `ModularityTests`를 확인한다.

## 변경 이력

- 2026-11-28: 작성·합의
- 2026-11-28: 구현 중 합의에 따라 관리자 예매 상세 응답에 선택적인 `cancelReason` 취소 유형을 추가
