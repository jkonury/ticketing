# ADR 0019: Batch Job을 데이터 소유 모듈에 둔다

- 상태: 수락
- 작성일: 2026-11-11
- 합의일: 2026-11-11
- 결정 주체: 사용자 합의
- 대체: 없음
- 대체됨·일부 변경: 없음
- 관련: [설계](../architecture.md), [ADR 0016](0016-provided-required-packages.md), [ADR 0017](0017-payment-owns-payment-events.md)

## 배경

M2에서는 정산, 미결제 예매 정리, 좌석 스냅샷 보정, 결제 대사처럼 서로 다른 모듈의 데이터와 규칙을 처리하는 Batch Job이 필요하다. Job을 한곳에 모으면 배포 런처가 각 도메인 모듈의 내부 포트를 알아야 하고, 모듈별 책임 경계가 흐려진다. 코어 Modulith 구조를 유지하면서 Batch를 별도 실행 모듈로 제공할 위치와 실행 방식을 정한다.

## 결정

1. Job과 Step 정의는 데이터를 소유한 모듈의 `adapter.provided.batch` 패키지에 둔다. Batch는 웹 컨트롤러와 같은 입력 어댑터로 취급하고, Job 설정은 `@Profile("batch")`로 활성화한다.
2. `core-batch`는 core 컨텍스트를 `batch` 프로필과 웹 서버 비활성화 설정으로 실행하는 런처다.
3. 결제 대사는 `payment` 모듈이 맡는다. PG 거래와 `payment.payment`의 `pg_transaction_id`, 상태, 금액을 같은 스키마 안에서 비교한다.
4. 정산 원천은 `BookingConfirmed`와 `BookingCancelled` 이벤트에 `performanceId`, `organizerId`를 추가해 제공한다. `settlement`는 booking 이벤트만 구독한다.

## 검토한 대안

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| `core-batch`의 `io.github.ticketing.batch` 패키지에 Job을 두고 모듈이 배치용 유스케이스를 `@NamedInterface`로 공개 | booking·settlement·payment의 내부 포트를 모듈 밖에 공개해야 하고, Job이 여러 모듈 내부를 알게 된다. |
| settlement가 결제 이벤트 사본을 적재하고 `orderId`로 대사 | 결제 이벤트에 PG 거래 ID가 없어 매칭 정밀도가 낮고, 사본 테이블과 구독이 늘어난다. MSA 분리 시 대사는 결제 서비스와 함께 움직이는 편이 자연스럽다. |
| settlement가 `ScheduleRegistered`도 구독해 회차에서 공연·주최자 사본을 구성 | settlement에서 catalog로 의존이 늘고, settlement 도입 전에 등록된 회차는 사본이 비어 있다. |

## 결과

- 얻는 것: Job이 데이터와 규칙을 소유한 모듈 경계 안에 남고, `core-batch`는 도메인 내부를 알지 않는 실행 진입점이 된다. 정산 이벤트만으로 공연·주최자별 집계가 가능하다.
- 감수하는 것: core에 Spring Batch 의존성이 생긴다. 웹 앱(`core`)에서는 Job 빈을 만들지 않고 자동 실행도 꺼야 한다. booking 이벤트 페이로드가 확장된다.
- 바뀌는 문서·지침: `docs/architecture.md`, `docs/roadmap.md`.

## 검증

모듈 의존 검증(`ApplicationModules.verify()`)으로 소유 모듈 바깥에서 내부 어댑터를 참조하지 않는지 확인한다. Batch 프로필에서만 Job 빈이 생성되고 `core-batch`에서 지정한 Job 하나가 실행되는지를 통합 검증한다.

## 변경 이력

- 2026-11-11: 사용자 합의에 따라 결정
