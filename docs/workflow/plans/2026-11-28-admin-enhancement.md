---
status: approved
approved_at: 2026-11-28T14:43:06+09:00
---

# 관리자 기능 강화

## 목표와 근거

관리자가 공연·예매·결제를 조회하고, 문제 공연을 판매 중지하고, 문제 회원을 이용 정지할 수 있게 한다. 상태 변경은 감사 로그에 남는다. 결정은 [ADR 0052](../../adr/0052-admin-intervention-states.md), 기존 관리자 구조는 [ADR 0034](../../adr/0034-platform-admin-role-and-audit.md)를 따른다. 로드맵 위치는 [후속 항목 표](../../roadmap.md#단계와-무관한-후속-항목)이며 M6 k8s 작업과 독립이다. 순서는 가시성(조회) → 개입(정지·중지) → 화면 → 문서다.

끝났을 때 관찰할 결과:

- 관리자가 `/admin/performances`, `/admin/bookings`(상세 포함)에서 조회·필터한다. 예매 상세에 결제 상태가 함께 보인다.
- 관리자가 회원을 이용 정지하면 그 회원의 로그인·갱신이 거부된다. 해제하면 다시 가능하다.
- 관리자가 공연을 판매 중지하면 공개 조회에서 사라지고 신규 예매가 거부된다. 재개하면 복구된다.
- 위 4가지 상태 변경이 감사 로그에 사유와 함께 보인다.

## 범위

- 포함: core(member·catalog·booking·audit), payment 앱의 관리자 조회, Gateway 라우트, OpenAPI 스펙과 BFF 경계 스키마 재생성, `web/admin` 화면, 아키텍처·API·이벤트·ERD 문서 갱신.
- 범위 밖: 예매 강제 취소·환불, 공연 취소 Saga, PENDING 예매 정리, 정산 전체 조회, 운영 도구(이벤트 재처리·배치 실행), 대시보드, 관리자 역할 세분화, 정지 사유의 회원 노출, access 토큰 즉시 무효화.

## task

### T1. 관리자 예매 조회 API (core)

- [x] 완료
- Blocked by: 없음
- 변경 범위와 제약: `booking` 모듈에 `GET /api/v1/admin/bookings`(회원 ID·회차 ID·상태·기간 필터, 커서 페이지네이션)와 `GET /api/v1/admin/bookings/{id}`(좌석, 금액, 쿠폰 적용 내역, 상태)를 추가한다. 기존 `booking/adapter/provided/web` 구조와 jOOQ 조회 어댑터를 따르고 `@PreAuthorize("hasRole('ADMIN')")`를 건다. 결제 정보는 core에서 조회하지 않는다. 다른 스키마와 조인하지 않는다(규칙 2).
- 테스트 경계와 선례: HTTP 공개 경계의 통합 테스트. 선례는 `AdminMemberIntegrationTests`. ADMIN은 200, USER·ORGANIZER는 403, 필터별 결과와 커서 이동, 없는 ID는 404를 확인한다.
- AC:

  ```bash
  ./scripts/agent/test/run-tests.sh :core:test --tests '*AdminBooking*'
  ```

### T2. 관리자 결제 조회 API (payment)

- [x] 완료
- Blocked by: 없음
- 변경 범위와 제약: payment 앱에 `bookingId`(= `orderId`)로 결제 상태·금액·환불을 조회하는 관리자 전용 API를 추가한다. 단독 목록 화면용 필터 API는 만들지 않는다. JWT는 payment 앱이 검증하며 ADMIN 역할만 허용한다. Gateway `payment-api` 라우트가 `/api/v1/admin/payments`를 payment로 보내는지 확인하고 필요하면 라우트와 문서를 고친다. `payment_db` 스키마 변경은 하지 않는다.
- 테스트 경계와 선례: payment 앱의 HTTP 통합 테스트. 선례는 `PaymentQueryController`의 테스트. ADMIN 200, 그 외 403, 결제 없는 `bookingId`는 빈 결과 또는 404(기존 `payments/me` 규칙과 같게)를 확인한다. Gateway 라우팅 테스트가 있으면 같은 선례로 추가한다.
- AC:

  ```bash
  ./scripts/agent/test/run-tests.sh :payment:test --tests '*AdminPayment*'
  ./scripts/agent/test/run-tests.sh :gateway:test
  ```

### T3. 회원 이용 정지·해제

- [x] 완료
- Blocked by: 없음
- 변경 범위와 제약: core Flyway에 `member.member`의 상태 컬럼(`ACTIVE`/`SUSPENDED`, 기본 `ACTIVE`, NOT NULL)을 추가한다. `PUT /api/v1/admin/members/{id}/suspension`(사유 필수 최대 200자)과 해제 API를 `AdminMemberService` 흐름에 맞춰 추가한다. 정지하면 refresh 토큰을 전부 폐기한다. `AuthService.login`·`refresh`는 정지 회원을 거부한다. 이때 이메일 존재 여부가 응답 시간·코드로 드러나지 않게 기존 해시 비교를 먼저 하고 자격 증명이 맞을 때만 정지 오류(`MEMBER_SUSPENDED`)로 응답하는 방식을 따른다. 자기 자신·ADMIN 정지는 거부하고, 같은 상태 재요청은 409로 응답한다. 회원 목록 응답에 상태를 추가한다. `MemberSuspensionChanged` 이벤트를 member가 발행하고 audit가 `MemberAuditListener` 방식으로 `admin_action`에 멱등 기록한다. 이벤트가 Kafka로 외부화되는 대상인지 [event-contract-change 스킬](../../../.claude/skills/event-contract-change/SKILL.md) 기준으로 확인하고, 아니면 core 내부 이벤트로 둔다.
- 테스트 경계와 선례: 선례는 `AdminMemberIntegrationTests`와 `AuthService` 테스트. 정지 후 로그인·갱신 거부, 해제 후 복구, 기존 access 토큰은 만료까지 유효함, 자기·ADMIN 정지 거부, 중복 요청 409, 감사 로그 1건과 재전달 멱등을 확인한다. 가입 직후 상태가 `ACTIVE`임을 확인한다.
- AC:

  ```bash
  ./scripts/agent/test/run-tests.sh :core:test --tests '*AdminMember*' --tests '*AuthService*' --tests '*Audit*'
  ```

### T4. 공연 판매 중지·재개 (catalog)

- [x] 완료
- Blocked by: 없음
- 변경 범위와 제약: core Flyway에 `catalog.performance` 상태 컬럼(`ACTIVE`/`SUSPENDED`, 기본 `ACTIVE`)을 추가한다. 관리자 API로 중지(사유 필수)·재개와 공연 목록(상태·주최자 ID·제목 필터, 커서)을 제공한다. 중지된 공연은 공개 목록에서 제외하고 상세는 404로 응답한다. 주최자의 공연 수정·회차 추가는 거부하되 판매 현황과 정산은 유지한다. 주최자 전용 `GET /api/v1/organizer/performances/{id}`에서 판매 중지된 자기 공연 상세도 조회할 수 있게 한다. 공개 조회 캐시(`CatalogCacheConfig`)가 있으므로 상태 변경 시 무효화한다. `PerformanceSuspensionChanged` 이벤트를 catalog가 발행하고 audit가 멱등 기록한다. 같은 상태 재요청은 409다. 이벤트 외부화 여부는 T3와 같은 방식으로 확인한다.
- 테스트 경계와 선례: 선례는 `CatalogQueryController`·`CatalogRegistrationController` 통합 테스트. 중지 후 목록 제외·상세 404, 주최자 수정·회차 추가 거부, 주최자 자기 공연 조회 유지, 재개 후 복구, 캐시 무효화(중지 직후 공개 조회에 반영), 감사 로그 기록을 확인한다.
- AC:

  ```bash
  ./scripts/agent/test/run-tests.sh :core:test --tests '*Catalog*' --tests '*AdminPerformance*' --tests '*Audit*' --tests '*ModularityTests*'
  ```

### T5. booking의 판매 중지 반영과 신규 예매 차단

- [ ] 완료
- Blocked by: T4
- 변경 범위와 제약: booking이 `PerformanceSuspensionChanged`를 구독해 `booking.schedule` 사본에 판매 가능 여부를 반영한다(`@ApplicationModuleListener`, 재전달 멱등, 상태 번호나 시각으로 순서 역전을 막는다). 신규 좌석 선점·예매 생성의 진입점에서 중지된 공연의 회차를 거부한다(`CreateBookingService`와 선점 경로를 확인하고 한 곳에서 판정한다). 기존 PENDING 예매의 결제·만료, CONFIRMED 예매 조회·취소는 막지 않는다. Redis 좌석 스냅샷·좌석 변경 스트림의 소비자(`realtime`)는 바꾸지 않는다.
- 테스트 경계와 선례: 선례는 `ScheduleRegisteredListener`와 `CreateBookingService` 테스트. 중지 후 신규 선점·예매 거부(코드 포함), 중지 전 만든 PENDING 예매의 결제 성공, CONFIRMED 취소 가능, 재개 후 신규 예매 성공, 이벤트 재전달·순서 역전에서 최종 상태 유지를 확인한다. `ModularityTests`를 같은 실행에 포함한다.
- AC:

  ```bash
  ./scripts/agent/test/run-tests.sh :core:test --tests '*ScheduleSuspension*' --tests '*CreateBooking*' --tests '*ModularityTests'
  ```

### T6. OpenAPI 스펙과 BFF 경계 스키마 재생성

- [ ] 완료
- Blocked by: T1, T2, T3, T4
- 변경 범위와 제약: [ADR 0047](../../adr/0047-openapi-codegen-for-bff-boundary.md)의 절차대로 core·payment 스펙(`docs/api/openapi/*.json`)과 `web`의 Zod 스키마를 재생성하고 drift 검사를 통과시킨다. 손으로 생성 파일을 고치지 않는다. `web/src/server/api/admin.ts` 계열에 예매·결제·공연·정지 호출 함수를 추가한다.
- 테스트 경계와 선례: 선례는 `web/src/server/api/admin.test.ts`. 요청 경로·본문·응답 파싱을 단위 테스트로 확인한다.
- AC:

  ```bash
  ./scripts/agent/test/verify-changed.sh
  ```

  (재생성 절차와 drift 검사 명령은 [ADR 0047](../../adr/0047-openapi-codegen-for-bff-boundary.md)·[프런트엔드 지침](../../rules/frontend.md)에서 확인해 AC에 구체 명령으로 바꿔 적는다.)

### T7. web 관리자 화면

- [ ] 완료
- Blocked by: T6
- 변경 범위와 제약: [프런트엔드 지침](../../rules/frontend.md)과 U 트랙 토큰·프리미티브 규칙을 따른다. `/admin/performances`(목록·필터·중지/재개 서버 액션, 사유 입력), `/admin/bookings`(목록·필터)와 상세(좌석·금액·쿠폰·상태, 결제 상태는 BFF가 payment를 호출해 합침, 결제 조회 실패 시 예매 정보는 그대로 보여 주고 결제 영역만 오류 표시), `/admin/members`에 상태 표시와 정지·해제 액션을 추가한다. 서버 액션마다 `requireAdmin`을 재검사한다. 관리자 메뉴 `layout.tsx`에 공연·예매 링크를 추가한다. JWT는 브라우저에 노출하지 않는다(규칙 5).
- 테스트 경계와 선례: 선례는 `features/admin/actions.test.ts`, `coupon-actions.test.ts`, `require-admin.test.ts`. 서버 액션의 권한 재검사·입력 검증·오류 매핑을 단위 테스트로 확인한다. 화면은 `ui-builder` 지시서 방식의 headless 캡처로 확인한다.
- AC:

  ```bash
  ./scripts/agent/test/verify-changed.sh
  ```

### T8. 문서 갱신

- [ ] 완료
- Blocked by: T1, T2, T3, T4, T5
- 변경 범위와 제약: [architecture.md](../../architecture.md)의 audit 이벤트 목록, [api-conventions.md](../../architecture/api-conventions.md)의 관리자 API 표와 Gateway 설명, [events.md](../../architecture/events.md), [erd.md](../../architecture/erd.md)와 [data-ownership.md](../../architecture/data-ownership.md)의 컬럼, [runbook](../../runbook.md)(정지 회원·중지 공연 운영 시 유의점), [로드맵](../../roadmap.md) 항목 체크를 구현된 상태로 맞춘다. 구현 중 설계와 달라진 점은 사용자와 합의 후 ADR 0052 대신 새 ADR이나 변경 이력으로 남긴다.
- 테스트 경계와 선례: 문서 링크·표기 검사.
- AC:

  ```bash
  ./scripts/agent/docs/check-docs.sh
  ```

## 최종 검증

- `./scripts/agent/test/verify-changed.sh <시작-SHA>..HEAD`와 필터 없는 `./scripts/agent/test/run-tests.sh`를 순서대로 실행한다. jOOQ 코드 생성과 Testcontainers에 Docker가 필요하다.
- 코어 모듈 의존이 바뀌면 [Modulith 검증·다이어그램 갱신 지침](../../rules/backend-core.md#코어-모듈-구조-modulith)을 따른다.
- `review-diff`로 시작 SHA부터 HEAD까지 검토한다. 이 계획과 ADR 0052를 명세로 쓴다.
- 전체 E2E는 PR의 CI에서 확인한다. 로컬 E2E는 요청이 있을 때만 실행한다.
