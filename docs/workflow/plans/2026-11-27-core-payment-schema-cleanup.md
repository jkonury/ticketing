---
status: approved
approved_at: 2026-11-27T17:02:55+09:00
---

# core DB의 결제 스키마 정리

## 목표와 근거

분리 후 core DB에 남겨 둔 `payment` 스키마를 제거한다. `payment_db`가 결제 데이터의 소유자이며, 검증 환경에서 제거 전후에 같은 결제·환불 동작을 확인한다. [ADR 0040](../../adr/0040-payment-separate-database-same-instance.md), [M6 분리 계획](../../plans/m6-msa.md), [W3 완료 기준](../roadmap.md#w3-plan-next-implement-계획-템플릿)

## 범위

- 포함: core Flyway 마이그레이션과 검증, 아키텍처의 데이터 소유·ERD 문서와 결제 DB 이전 런북 갱신.
- 범위 밖: 운영·공유 DB에 적용, 결제 데이터 추가 이전, `payment_db`의 마이그레이션·계정·계약 변경, settlement 분리와 Saga 재검증.

## task

### T1. core Flyway에서 이전이 끝난 결제 스키마 제거

- [ ] 완료
- Blocked by: 없음
- 변경 범위와 제약: 적용된 SQL은 보존하고 core에 새 Flyway 마이그레이션을 추가한다. 제거할 기존 결제 객체를 명시하고, 예상하지 못한 객체나 다른 스키마의 의존 관계가 있으면 마이그레이션이 실패하도록 한다. `payment_db`의 스키마는 유지한다. [아키텍처](../../architecture.md#5-데이터), [데이터 소유](../../architecture/data-ownership.md), [ERD](../../architecture/erd.md)에 현재 상태를 반영하고 [런북](../../runbook.md#9-결제-db-이전-core-db-payment-스키마--payment_db)에 운영 적용 전 이전 완료 확인과 백업 보존 조건을 적는다.
- 테스트 경계와 선례: `core`의 `TicketingApplicationTests`에서 애플리케이션 기동 후 DB 스키마 목록을 확인한다. 마이그레이션을 추가하기 전에는 기존 `payment` 스키마가 남아 실패해야 하고, 적용 뒤에는 `payment`가 사라지고 사용 중인 다른 스키마는 남아야 한다. Flyway와 jOOQ 코드 생성은 격리된 테스트 DB를 사용한다.
- AC:

  ```bash
  ./scripts/agent/test/run-tests.sh :core:test --tests '*TicketingApplicationTests'
  ./scripts/agent/docs/check-docs.sh
  ```

## 최종 검증

- `./scripts/agent/test/verify-changed.sh <시작-SHA>..HEAD`와 필터 없는 `./scripts/agent/test/run-tests.sh`를 순서대로 실행한다.
- `./scripts/e2e/run.sh`는 공유 볼륨을 사용하지 않는 테스트용 Compose 환경에서 실행한다. 이때 core에는 새 마이그레이션을 적용하고, `PaymentFlowE2eTests`의 승인 웹훅 멱등, 만료 후 환불·좌석 반환, 웹훅 라우팅·서명 검증의 통과를 확인한다. 결제 DB의 `payment` 스키마가 존재하는지도 확인한다.
- `review-diff`로 시작 SHA부터 HEAD까지 검토한다. 이 계획을 명세로 사용하고 Standards·Spec 및 게이트 기록을 확인한다.
