# ADR 0035: coupon 모듈과 예약 Saga

- 상태: 합의
- 작성일: 2026-11-17
- 합의일: 2026-11-17
- 결정 주체: 2026-11-17 M5.5 설계 인터뷰(Q5, Q8, Q9, Q10, Q11, Q15)
- 대체: 없음
- 대체됨·일부 변경: 없음
- 관련: [계획](../plans/m5-5-coupon.md), [ADR 0017](0017-payment-owns-payment-events.md), [ADR 0034](0034-platform-admin-role-and-audit.md), [ADR 0036](0036-settlement-list-price-platform-discount.md)

## 배경

예매에 쿠폰을 적용하면 보상 대상이 좌석과 쿠폰 두 가지가 된다. 쿠폰은 새 `coupon` 모듈(헥사고날)로 만들고, 모듈 간 호출은 이벤트로만 해야 한다. 이때 coupon이 `BookingConfirmed`를 구독하면 `coupon → booking` 의존이 생겨 `booking → coupon`과 순환한다. ADR 0017이 payment 이벤트를 payment 루트에 둔 것처럼 이 문제를 풀어야 한다.

## 결정

**모듈 경계**
1. 의존 방향은 `booking → coupon` 하나다. 사용 확정·해제·예약 요청 이벤트도 coupon 루트 패키지가 소유하고 booking이 발행한다. coupon은 booking을 참조하지 않는다.
2. 계약 이벤트: `CouponReservationRequested`, `CouponReleaseRequested`, `CouponUseConfirmed`(booking 발행)와 `CouponReserved`, `CouponRejected`(coupon 발행).

**금액**
3. coupon이 할인액을 계산한다. booking은 `listAmount`·`discountAmount`·`payableAmount`를 저장하고, 결제 금액 비교는 `payableAmount`로 한다. 할인 결과가 0원 이하이거나 정가 이상이면 거부한다(결제 최소 1원).

**사용 규칙**
4. 예매당 쿠폰 1장, 회원·정의당 1장 발급(유니크 제약).
5. 만료·결제 실패·시스템 취소(좌석 충돌·금액 불일치)·결제 전 사용자 취소는 쿠폰을 복원한다. 확정 후 환불은 쿠폰이 소멸한다.
6. 쿠폰은 PENDING 예매에 별도 적용·해제·교체 API로 붙인다. 예약이 진행 중(RESERVING)이면 결제 요청을 거부한다(409).

**경합과 멱등**
7. 쿠폰이 거부되면 쿠폰 없이 PENDING을 유지하고 사유를 저장한다.
8. 예매가 이미 PENDING이 아니거나 해제된 뒤 도착한 예약 결과는 즉시 해제 요청을 보낸다. 해제 메시지가 끝내 유실된 고아 예약은 정리 Job이 해제한다.
9. 늦게 도착한 사용 확정은 상태가 해제·발급 상태여도 강제로 `USED`로 바꾸고 불일치를 기록한다. 결제가 이미 확정된 사실이 우선이다.
10. 상태 전이는 조건부 UPDATE로 하고 `(orderId, issuedCouponId)`가 같은 재전달은 무시한다.

**정의 수명주기**
11. 발급이 시작된 뒤에는 할인 조건을 수정할 수 없다(이름·설명, 종료일 연장만). `STOPPED`는 신규 발급만 막는다. 회수는 `ISSUED` 상태에만 가능하다.

## 검토한 대안

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| coupon이 `BookingConfirmed`를 구독 | `coupon → booking` 의존이 생겨 순환한다. ADR 0017과 같은 이유로 소유 모듈 루트에 이벤트를 둔다. |
| booking이 coupon을 동기 호출 | 모듈 간 호출은 이벤트로만 한다는 규칙에 어긋나고, 두 모듈을 하나의 트랜잭션으로 묶게 된다. M6 분리도 어려워진다. |
| booking 안에서 할인 계산 | 쿠폰 규칙이 booking으로 새어 들어가 모듈 경계가 무의미해진다. |
| 예매 생성 때 쿠폰을 함께 적용 | 결제 화면에서 쿠폰을 바꾸는 흐름을 지원하지 못한다. 적용·해제·교체를 별도 API로 둔다. |
| 쿠폰 복수 적용 | 할인 계산·복원 규칙이 복잡해지고 학습 목표(Saga 정합성)에서 얻는 것이 적다. |
| 환불 시 쿠폰 복원 | 정산 부담액 정정과 악용 여지가 생긴다. 소멸로 단순화하고 비용은 [ADR 0036](0036-settlement-list-price-platform-discount.md)에서 다룬다. |
| 거부 시 예매 실패 처리 | 사용자는 쿠폰 없이도 결제할 수 있어야 한다. 좌석 점유를 불필요하게 잃는다. |
| 늦은 사용 확정을 거부 | 결제는 이미 끝났다. 거부하면 쿠폰이 재사용되어 할인이 이중 적용된다. |

## 결과

- 얻는 것: 순환 없는 의존, 좌석·쿠폰 두 자원에 대한 보상 흐름을 이벤트로 검증할 수 있다.
- 감수하는 것: 예약 결과가 비동기이므로 RESERVING 상태와 폴링이 생긴다. 고아 예약 정리 Job이 필요하다. 이벤트 종류가 늘어난다.
- 바뀌는 문서·지침: `docs/architecture.md`의 모듈·이벤트·Saga 절, `docs/roadmap.md` M5.5-2 (별도 작업에서 갱신).

## 변경 이력

- 2026-11-17: 작성, 합의
