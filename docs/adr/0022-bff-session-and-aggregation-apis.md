# 0022. BFF 세션과 조합 API

- 상태: 수락
- 작성일: 2026-11-12
- 합의일: 2026-11-12
- 결정 주체: 사용자 요청 반영
- 대체: 없음
- 대체됨·일부 변경: 없음
- 관련: [설계](../architecture.md), [인증·BFF 연동 설계](../architecture/integrations.md), [Redis 설계](../architecture/redis.md), [핵심 흐름](../architecture/flows.md), [ADR 0013](0013-jwt-behind-bff.md), [ADR 0014](0014-sse-bypasses-bff.md), [ADR 0020](0020-gateway-verification-contracts.md), [ADR 0021](0021-realtime-queue-and-sse-contracts.md)

## 배경

M4에서 Next.js를 화면과 BFF로 사용해 인증을 유지하고 여러 소유 모듈의 데이터를 조합한다. ADR 0013은 브라우저에 JWT를 노출하지 않고 BFF가 토큰을 보관하도록 정했지만 세션 저장소와 동시 갱신 방식은 남겨 두었다. 조합 화면의 데이터 계약과 좌석 SSE 재연결 방식도 구현 전에 구체화할 필요가 있다.

이 ADR은 ADR 0013·0014·0020·0021을 구체화하며 대체하지 않는다.

## 검토한 대안

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| BFF 프로세스 메모리에 세션 보관 | 재시작 시 로그아웃되고 여러 인스턴스에서 세션을 공유할 수 없다. |
| 암호화 쿠키에 토큰 저장 | 암호문이어도 토큰이 브라우저 저장소에 실려, M4 완료 기준인 브라우저 어디에도 JWT가 없다는 조건을 모호하게 한다. |
| 로그아웃 때 BFF 세션만 삭제 | refresh 토큰이 남은 수명(14일) 동안 유효하게 남는다. |
| 기존 API를 조합해 근사 | 제목 매칭은 틀릴 수 있고, 예매 상태로 결제 상태를 대신하거나 정산으로 판매 현황을 대신하면 조합과 부분 실패의 의미가 사라진다. |
| `Last-Event-ID` 헤더를 위해 fetch 기반 SSE를 구현 | 브라우저에서 스트림 파서와 재연결을 직접 구현해야 하며 preflight가 생긴다. |

## 결정

1. **BFF 세션은 Redis에 둔다.** `bff:session:<sessionId>`에 토큰과 만료 시각을 저장하고 쿠키에는 불투명 세션 ID만 둔다. 세션 TTL은 refresh 토큰 수명인 14일이다.
2. **토큰 갱신은 세션당 한 번만 일어나게 한다.** 프로세스 안 single-flight와 Redis `bff:refresh-lock:<sessionId>`의 `SET NX PX`를 함께 쓴다. refresh 토큰은 로테이션되고 재사용이 감지되면 회원의 모든 refresh 토큰이 폐기되므로 동시 갱신을 막는다. 갱신 실패 시 세션을 파기한다.
3. **로그아웃은 코어의 refresh 토큰 폐기 API를 호출한다.** 공개·멱등 API `POST /api/v1/auth/tokens/revoke`를 호출하며, 폐기 결과와 관계없이 BFF 세션을 지운다.
4. **조합에 필요한 조회 API를 소유 모듈에 추가한다.** 각 모듈은 자기 스키마만 조회한다.

   | 모듈 | API | 내용 |
   | --- | --- | --- |
   | member | `POST /api/v1/auth/tokens/revoke` | 공개. 본문 `refreshToken`. 해시로 찾아 폐기하며 없어도 204 |
   | payment | `GET /api/v1/payments/me?bookingIds=1,2` | USER. 최대 50개. 본인 결제만 `[{bookingId, status, failureReason?, approvedAt?, refundedAt?}]` |
   | booking | `GET /api/v1/schedules/availability?scheduleIds=1,2` | 공개. 최대 50개. `[{scheduleId, totalSeats, availableSeats}]`. 가용 좌석은 좌석 수에서 판매 좌석과 만료 전 PENDING 예매 좌석을 뺀 값 |
   | booking | `GET /api/v1/bookings/me` 확장 | 항목에 `performanceId`를 포함하고 `?performanceId=` 필터를 제공 |
   | booking | `GET /api/v1/organizer/sales` | ORGANIZER. JWT 회원 ID가 `booking.schedule.organizer_id`와 일치하는 회차별 `{performanceId, performanceTitle, scheduleId, startsAt, totalSeats, soldSeats, confirmedAmount}` |

5. **좌석 SSE는 `lastEventId` 쿼리로도 이어 받는다.** SSE 티켓은 1회용이라 브라우저 `EventSource` 자동 재연결은 같은 URL을 재사용할 수 없고, 새 티켓으로 직접 재연결할 때 `Last-Event-ID` 헤더를 실을 수 없다. realtime 좌석 스트림은 헤더가 없으면 `?lastEventId=`를 사용하며, 헤더가 있으면 헤더를 우선한다.
6. **입장 토큰은 브라우저가 대기열 SSE로 받아 BFF에 넘긴다.** 토큰은 불투명 난수이며 JWT가 아니다. 예매 생성 요청에서 BFF가 `X-Admission-Token`으로 전달한다.

## 결과

- Redis 의존성이 BFF에 추가된다.
- 화면 요구에 따라 코어 API가 늘어난다.
- `lastEventId`가 URL에 남지만 비밀 값이 아니므로 허용한다.

## 검증

step 5의 동시 갱신 단위 테스트(웹 단위 테스트)와 step 9의 JWT 비노출 검사(Playwright E2E)로 확인한다.
