# 인증·BFF·외부 연동 (§7, §8, §10)

> [← 아키텍처 개요](../architecture.md) · 범위: §7 인증·인가, §8 BFF, §10 외부 연동(fake-pg 계약)
> 함께 보기: [flows.md](./flows.md), [redis.md](./redis.md)

## 7. 인증·인가

- `member` 모듈이 JWT(access/refresh)를 직접 발급한다.
- **BFF가 토큰을 보관·갱신**한다. 브라우저에는 httpOnly 쿠키 세션만 내려가고 토큰은 노출되지 않는다.
- 로그아웃 시 BFF는 공개·멱등 `POST /api/v1/auth/tokens/revoke`를 호출해 refresh 토큰을 폐기한 뒤 세션을 지운다. 호출이 실패해도 BFF 세션은 삭제한다 ([ADR 0022](../adr/0022-bff-session-and-aggregation-apis.md)).
- 구현 세부 (M1-1):
  - access JWT는 HS256(대칭키, `JWT_SECRET` 환경 변수), 수명 15분, `sub`=회원 ID, `role` 클레임. 비대칭키(JWKS)는 MSA 전환 시 재검토한다.
  - refresh 토큰은 불투명 난수(수명 14일)이고 SHA-256 해시만 `member.refresh_token`에 저장한다. 갱신할 때마다 이전 토큰을 폐기(로테이션)하며, 폐기된 토큰이 재사용되면 그 회원의 모든 refresh 토큰을 폐기한다.
  - 비밀번호는 BCrypt. 가입 시 역할을 직접 고를 수 있다(플랫폼 관리자가 없어 주최자 승인 절차를 두지 않는다).
- Gateway는 `Authorization: Bearer`가 있으면 모든 경로에서 같은 HS256 비밀로 JWT 서명을 검증하고, 실패 시 401 `UNAUTHORIZED`로 응답한다. 클라이언트가 보낸 `X-Member-Id`는 지우며 realtime 경로에만 검증된 회원 ID를 전달한다. 코어도 JWT를 자체 검증한다 ([ADR 0020](../adr/0020-gateway-verification-contracts.md)).
- `POST /api/v1/sse-tickets`는 JWT 인증된 요청에서 코어 `member`가 티켓을 발급한다. 티켓은 Redis `sse:ticket:<ticket>`에 회원 ID로 저장하고 TTL은 30초이며, Gateway가 한 번 소비한다 ([ADR 0020](../adr/0020-gateway-verification-contracts.md)).
- 역할: **일반 사용자**(예매), **주최자**(공연·회차 등록, 정산 조회). 역할 기반 인가 적용.
- SSE는 BFF를 거치지 않는다. BFF가 발급한 **단기 SSE 티켓**으로 브라우저가 Gateway에 직접 연결한다. (Node 프로세스가 장기 연결을 붙드는 것을 피하기 위함)

## 8. BFF (Next.js)

- 일반 API 요청은 전부 BFF를 거친다.
- 세션은 Redis `bff:session:<sessionId>`에 회원 ID, 이름, 역할, access·refresh 토큰과 access 만료 시각을 저장하며 TTL은 14일이다. 쿠키 `ticketing_session`에는 불투명 세션 ID만 담고 `HttpOnly`, `SameSite=Lax`, `Path=/`, 14일 만료를 설정한다. `SESSION_COOKIE_SECURE` 환경 변수로 `Secure`를 켜며 로컬 HTTP에서는 끈다.
- access 토큰은 만료 30초 전 또는 하위 API가 401을 반환하면 갱신한다. 세션당 한 번만 갱신하도록 프로세스 안 single-flight와 Redis 락을 사용하며, 갱신 실패 시 세션을 파기하고 로그인으로 보낸다.
- 상태를 바꾸는 Route Handler는 `Origin`(없으면 `Referer`)이 web origin과 같은지 검사한다. Server Action도 Next 내장 검사에 더해 같은 검사를 적용한다.
- 조합(Aggregation) 화면
  - 공연 상세: 공연 정보는 핵심 데이터, 회차별 잔여 좌석과 내 예매 여부는 부가 데이터다.
  - 마이페이지: 예매 목록은 핵심 데이터, 결제 상태는 부가 데이터다.
  - 주최자 대시보드: 판매 현황과 정산은 둘 다 부가 데이터이며 둘 다 실패하면 오류 화면을 표시한다.
- 조합에 사용하는 코어 API는 `GET /api/v1/schedules/availability?scheduleIds=...`, 확장된 `GET /api/v1/bookings/me?performanceId=...`, `GET /api/v1/payments/me?bookingIds=...`, `GET /api/v1/organizer/sales`다. 각 API는 소유 모듈의 스키마만 조회한다.
- 세션·갱신·조합 API 결정은 [ADR 0022](../adr/0022-bff-session-and-aggregation-apis.md)를 따른다.
- 예매 화면은 공개 `GET /api/v1/schedules/{id}`에서 공연 ID·제목과 회차 시각을 조회한다 ([ADR 0023](../adr/0023-schedule-detail-api.md)).
- 주최자 화면은 최소한으로 만들고, 초기 공연 데이터는 시드 스크립트로 넣는다.

### BFF Route Handler

브라우저가 호출하는 Route Handler는 `web/src/app/api/` 아래에 둔다.

| 경로 | 메서드 | 역할 |
|---|---|---|
| `/api/bookings` | POST | 좌석 예매 생성 |
| `/api/bookings/[id]` | GET | 예매 상태 조회 |
| `/api/bookings/[id]/cancel` | POST | 예매 취소 |
| `/api/bookings/[id]/payments` | POST | 결제 요청 |
| `/api/queues/[scheduleId]/entries` | POST | 대기열 진입 |
| `/api/queues/[scheduleId]/entries/me` | GET | 내 대기열 상태 조회 |
| `/api/streams/tickets` | POST | SSE 연결용 단기 티켓 발급 |

## 10. 외부 연동

- 결제: **가짜 PG 서버**를 직접 만든다. 지연, 타임아웃, 실패, 중복 웹훅을 설정으로 재현한다. 출력 포트로 추상화하여 추후 실제 PG 어댑터를 추가할 수 있게 한다.
- 알림: 로그 출력으로 시작.

### fake-pg 연동 계약

- 결제: `POST /v1/payments`, `Idempotency-Key`, 본문 `{orderId: string, amount: 정수 원 단위}`. 환불: `POST /v1/payments/{transactionId}/refund`, `Idempotency-Key`. 유효한 접수 응답은 202다.
- 거래 조회: `GET /v1/transactions?date=YYYY-MM-DD`의 요청 날짜는 Asia/Seoul 기준이다.
- 웹훅: 코어의 `POST /api/v1/payments/webhooks/fake-pg`로 승인·실패·환불 완료를 전송한다. eventId로 중복 제거하고 transactionId와 orderId·금액의 일치를 검증한다. 동일 거래의 같은 결과는 다른 eventId여도 다시 발행하지 않는다.
- 서명: `X-FakePg-Timestamp`(epoch 초), `X-FakePg-Signature`(hex HMAC-SHA256, 입력은 timestamp·점·원문 바이트). 코어와 PG가 환경 변수 `FAKE_PG_WEBHOOK_SECRET`을 공유한다. 원문으로 검증한 뒤 파싱하며 시각 허용 오차 기본값은 5분이다.
- 어댑터 기본 주소는 `http://localhost:8090`, 연결·읽기 타임아웃은 각각 3초다. 유효한 202는 Accepted, 4xx는 Rejected, 타임아웃·연결 실패·5xx·잘못된 접수 응답은 Unknown으로 분류한다. 재처리는 [6.4절](./flows.md#64-결제-이벤트-기반) 정책을 따른다.
- SUCCESS·FAIL·TIMEOUT·DELAYED·DUPLICATE_WEBHOOK 시나리오는 PG 설정으로 제어한다. TIMEOUT은 HTTP 응답만 늦어도 승인이 진행될 수 있다. fake-pg 자체의 메모리 거래 저장소는 재시작하면 초기화된다.
