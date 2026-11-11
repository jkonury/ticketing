# 테스트 구조 이동 목록

작성일: 2026-11-12. 변경 전 소스의 파일과 테스트 이름을 기준으로 기록한다. 각 시나리오의 검증은 보존하며, 단위 후보는 인프라 검증을 통합에 남기고 규칙 분기만 별도 단위 테스트로 추가·이동한다. 아래 목록의 대상은 구현 전 계획이며 완료 표시가 아니다.

## 파일과 시나리오별 대상

단위는 `src/test`, 통합·구조는 `src/integrationTest`, 문서 생성은 명시적 문서 작업, 별도 프로세스 E2E는 기존 `e2e` 실행 경로다. 테스트 지원 파일은 사용하는 소스 세트로 옮기거나 필요시 분리한다.

- `core/src/test/kotlin/io/github/ticketing/ApiExceptionHandlerTests.kt` — 단위: 낙관적 락 충돌은 409 CONCURRENT_MODIFICATION으로 변환된다
- `core/src/test/kotlin/io/github/ticketing/BatchInfrastructureTests.kt` — 통합: 배치 저장소는 batch 스키마에 작업 인스턴스를 저장한다
- `core/src/test/kotlin/io/github/ticketing/BatchIntegrationTestSupport.kt` — 지원 코드 또는 리소스: 사용처에 따라 분리
- `core/src/test/kotlin/io/github/ticketing/DocumentationTests.kt` — 문서 생성 작업: 모듈 의존 다이어그램을 생성한다
- `core/src/test/kotlin/io/github/ticketing/HexagonalArchitectureTests.kt` — 구조 검증: 헥사고날 모듈은 adapter에서 application으로 application에서 domain으로만 의존한다; 도메인은 Spring JPA jOOQ에 의존하지 않는다; main 코드는 문자열 SQL을 쓰지 않는다; main 코드는 EntityManager를 직접 의존하지 않는다; Query 애너테이션에 nativeQuery를 쓰지 않는다
- `core/src/test/kotlin/io/github/ticketing/IntegrationTestSupport.kt` — 지원 코드 또는 리소스: 사용처에 따라 분리
- `core/src/test/kotlin/io/github/ticketing/KeysetItemReaderTests.kt` — 단위: 페이지 경계를 넘어 순서대로 읽고 끝나면 null을 반환한다; 빈 결과는 null을 반환한다; 마지막 반환 키를 저장해 새 reader에서 이어 읽는다
- `core/src/test/kotlin/io/github/ticketing/ModularityTests.kt` — 구조 검증: 모듈 경계와 의존 방향을 검증한다; 설계한 6개 모듈이 모두 인식된다
- `core/src/test/kotlin/io/github/ticketing/TestcontainersConfiguration.kt` — 지원 코드 또는 리소스: 사용처에 따라 분리
- `core/src/test/kotlin/io/github/ticketing/TicketingApplicationTests.kt` — 통합: 컨텍스트가 뜨고 모듈별 스키마가 생성된다
- `core/src/test/kotlin/io/github/ticketing/booking/BookingModuleTests.kt` — 통합: 회차 등록 이벤트를 수신하면 좌석 사본을 저장한다
- `core/src/test/kotlin/io/github/ticketing/booking/adapter/provided/batch/ExpirePendingBookingsJobTests.kt` — 통합: 낙관적 락 충돌은 다른 예매를 되돌리지 않고 다음 실행에서 처리한다; 만료 대상만 처리하고 재실행해도 이벤트를 다시 발행하지 않는다
- `core/src/test/kotlin/io/github/ticketing/booking/adapter/provided/batch/ReconcileSeatSnapshotJobTests.kt` — 통합: 시작 전 회차만 보정하고 재실행은 변경하지 않는다
- `core/src/test/kotlin/io/github/ticketing/booking/adapter/provided/event/ScheduleRegisteredListenerTests.kt` — 통합: ScheduleRegistered를 받으면 사본이 생기고 같은 이벤트를 다시 받아도 행 수가 같다
- `core/src/test/kotlin/io/github/ticketing/booking/adapter/provided/web/BookingApiIntegrationTests.kt` — 통합 + 규칙 분기 단위 후보: 예매를 만들면 201과 PENDING을 돌려주고 Redis에 선점이 생긴다; 같은 멱등 키로 다시 요청하면 200과 같은 bookingId를 돌려준다; 5매를 요청하면 400이다; 멱등 키가 없거나 100자를 넘으면 400이다; 다른 사용자가 같은 좌석을 요청하면 409와 좌석 목록을 돌려준다; 없는 좌석은 404 SEAT_NOT_FOUND 없는 회차는 SCHEDULE_NOT_FOUND이다; 주최자 역할이나 토큰 없이 예매하면 403 401이다; 내 예매 목록은 ID 내림차순 커서로 페이지를 나눈다; 내 예매 상세는 좌석 라벨을 포함하고 남의 예매는 404로 감춘다; PENDING 예매를 취소하면 선점이 풀려 같은 좌석을 다시 선점할 수 있고 재취소는 같은 결과다; 남의 예매를 취소하면 403이다
- `core/src/test/kotlin/io/github/ticketing/booking/adapter/required/jpa/BookingPersistenceAdapterTests.kt` — 통합: 저장하고 조회하면 좌석과 상태가 그대로 복원된다; 상태 전이를 저장하면 버전이 오르고 취소 시각이 남는다; 오래된 버전으로 저장하면 낙관적 락 예외가 난다; 같은 회원과 멱등 키는 조회되고 중복 저장은 유니크 제약으로 거부된다; 같은 회차 좌석을 두 번째로 넣으면 삽입 수가 0이다; 판매 좌석을 지우면 같은 좌석을 다시 넣을 수 있다
- `core/src/test/kotlin/io/github/ticketing/booking/adapter/required/jpa/ScheduleSeatPersistenceAdapterTests.kt` — 통합: 사본을 저장하면 좌석을 조회할 수 있고 두 번째 저장은 건너뛴다; 좌석 저장은 사전 SELECT 없이 INSERT만 실행한다
- `core/src/test/kotlin/io/github/ticketing/booking/adapter/required/redis/RedisSeatHoldAdapterTests.kt` — 통합: 선점하면 TTL이 걸리고 스냅샷과 Stream에 기록된다; 이미 선점된 좌석이 있으면 아무것도 선점하지 않고 해당 좌석을 돌려준다; 다른 owner의 해제는 무시하고 소유자 해제는 AVAILABLE로 돌린다; 판매 후에는 선점 키가 사라져도 재선점할 수 없고 판매 해제 후에는 가능하다; 선점 키 TTL이 끝나면 스냅샷이 HELD여도 다시 선점할 수 있다; initSnapshot은 이미 있는 필드를 덮어쓰지 않는다; 스레드 50개가 같은 좌석을 선점하면 정확히 1건만 성공한다; 좌석이 겹치는 다중 선점은 한쪽만 성공하고 실패한 쪽은 아무것도 선점하지 못한다; 스냅샷을 판매와 선점 기준으로 보정하고 변경만 발행한다; 없는 스냅샷을 만들고 변경이 없으면 다시 발행하지 않는다
- `core/src/test/kotlin/io/github/ticketing/booking/application/flow/BookingPaymentFlowTests.kt` — 통합 + 규칙 분기 단위 후보: 결제를 요청하면 PaymentRequested가 한 번 발행되고 재요청해도 늘지 않는다; 멱등 키 헤더가 없으면 400이고 남의 예매는 403이다; 만료된 예매의 결제 요청은 409 BOOKING_EXPIRED이고 EXPIRED로 저장되며 좌석을 다시 선점할 수 있다; 결제가 성공하면 CONFIRMED와 sold_seat와 Redis SOLD가 생기고 같은 성공이 다시 와도 같다; 결제가 실패하면 CANCELLED가 되고 좌석을 다시 선점할 수 있다; 만료 후 결제 성공이 오면 EXPIRED가 되고 RefundRequested가 발행된다; 다른 예매가 이미 sold_seat를 가진 좌석이면 CANCELLED SEAT_ALREADY_SOLD와 RefundRequested가 생긴다; 결제 금액이 예매 금액과 다르면 CANCELLED AMOUNT_MISMATCH와 RefundRequested가 생기고 좌석이 반환된다; 확정된 예매에 결제를 다시 요청하면 409 INVALID_BOOKING_STATE이다; 취소된 예매에 결제를 다시 요청하면 409 INVALID_BOOKING_STATE이다; 이미 결제를 요청한 PENDING은 만료 시각이 지나도 재요청에 202를 준다; 확정된 예매를 취소하면 sold_seat가 삭제되고 RefundRequested가 발행되며 Redis가 AVAILABLE이 된다
- `core/src/test/kotlin/io/github/ticketing/booking/application/service/CreateBookingServiceTests.kt` — 단위: 저장이 실패하면 선점을 해제하고 예외를 다시 던진다; 정상 생성하면 PENDING을 저장하고 좌석 라벨을 돌려준다; 같은 멱등 키로 다시 요청하면 기존 예매를 돌려주고 선점하지 않는다; 선점에 실패하면 SEAT_ALREADY_HELD이고 아무것도 저장하지 않는다; 없는 좌석이나 회차면 404이고 선점하지 않는다; 5매는 도메인 규칙으로 400 예외가 되고 선점하지 않는다
- `core/src/test/kotlin/io/github/ticketing/booking/domain/BookingTests.kt` — 단위: 생성하면 PENDING이고 만료 시각은 5분 뒤이며 총액은 좌석 가격의 합이다; 좌석이 4개면 생성된다; 좌석이 5개면 TOO_MANY_SEATS; 좌석이 없으면 NO_SEATS; 중복 좌석이면 DUPLICATE_SEAT; 만료 시각 정각부터 만료로 판정한다; 일괄 만료는 미요청 예매의 만료 시각과 결제 요청 예매의 유예 경계를 지킨다; 일괄 만료는 PENDING이 아닌 예매를 변경하지 않는다; 미만료 PENDING은 결제를 요청할 수 있고 재요청은 시각을 바꾸지 않는다; 만료된 PENDING은 결제를 요청할 수 없다; PENDING이 아니면 결제를 요청할 수 없다; 미만료 PENDING에 금액이 맞는 결제 성공이 오면 확정 대상이 되고 confirm하면 CONFIRMED가 된다; 만료된 PENDING에 결제 성공이 오면 EXPIRED가 되고 환불이 필요하다; 미만료 PENDING에 금액이 다른 결제 성공이 오면 AMOUNT_MISMATCH로 CANCELLED가 된다; 금액은 값이 같으면 스케일이 달라도 일치한다; 만료된 PENDING은 금액이 달라도 만료가 먼저 판정된다; PENDING이 아니면 confirm할 수 없다; 결제 성공이 두 번 와도 상태가 다시 바뀌지 않는다; EXPIRED에 결제 성공이 오면 환불이 필요하고 상태는 그대로다; CANCELLED에 결제 성공이 오면 환불이 필요하고 상태는 그대로다; 결제 실패가 오면 PENDING이 PAYMENT_FAILED 사유로 CANCELLED가 된다; 결제 실패가 PENDING이 아닌 예매에 오면 무시한다; 확정 실패는 PENDING을 SEAT_ALREADY_SOLD 사유로 CANCELLED로 바꾼다; PENDING이 아니면 확정 실패 처리를 할 수 없다; 만료 시각이 지난 PENDING은 만료 처리된다; 만료 시각 전에는 만료 처리할 수 없다; PENDING이 아니면 만료 처리할 수 없다; PENDING을 본인이 취소하면 선점 해제 결과와 USER_REQUESTED 사유를 얻는다; CONFIRMED를 본인이 취소하면 환불이 필요하다; 본인 예매가 아니면 취소할 수 없다; EXPIRED와 CANCELLED는 취소할 수 없다
- `core/src/test/kotlin/io/github/ticketing/catalog/CatalogDomainTests.kt` — 단위: 좌석이 없는 공연장은 만들 수 없다; 좌석 키가 중복되면 공연장을 만들 수 없다; 좌석이 유효하면 공연장을 만든다; 음수 가격은 허용하지 않는다; 가격표가 비어 있으면 공연을 만들 수 없다; 가격이 0이어도 공연을 만든다
- `core/src/test/kotlin/io/github/ticketing/catalog/CatalogQueryApiTests.kt` — 통합: 공연 목록은 ID 내림차순 커서로 페이지를 넘기고 마지막 페이지의 nextCursor는 null이다; size는 최대 100으로 제한된다; 공연 상세는 공연장, 등급별 가격, 회차 목록을 담는다; 회차 좌석 배치는 좌석과 등급·가격을 담고 상태는 담지 않는다; 조회 API는 비인증 요청도 허용한다; 같은 상세를 다시 조회하면 캐시에서 오고 회차를 등록하면 갱신된다
- `core/src/test/kotlin/io/github/ticketing/catalog/CatalogRegisterApiTests.kt` — 통합 + 규칙 분기 단위 후보: 주최자는 공연장을 등록하면 좌석 수를 돌려받는다; 일반 사용자는 403, 비인증 요청은 401을 받는다; 좌석이 없거나 5000개를 넘으면 400이다; 같은 구역·열·번호의 좌석이 중복되면 400이다; 주최자는 공연을 등록한다; 공연장 좌석 등급의 가격이 없으면 400이다; 없는 공연장으로 공연을 등록하면 404다; 회차를 등록하면 좌석 수만큼의 좌석과 가격을 담은 이벤트가 발행된다; 다른 주최자의 공연에는 회차를 등록할 수 없고 이벤트도 발행되지 않는다; 없는 공연에 회차를 등록하면 404다; 같은 시작 시각의 회차를 다시 등록하면 409이고 이벤트는 한 번만 발행된다
- `core/src/test/kotlin/io/github/ticketing/catalog/CatalogRepositoryTests.kt` — 통합: 공연장과 좌석을 저장하고 조회한다; 공연과 등급별 가격, 회차를 저장하고 조회한다; 같은 공연에 같은 시작 시각의 회차는 저장할 수 없다; DB는 같은 공연장의 중복 좌석 키를 거부한다
- `core/src/test/kotlin/io/github/ticketing/catalog/CatalogSeedRunnerTests.kt` — 통합: 시드를 실행하면 공연 3편이 생기고 다시 실행해도 늘지 않는다
- `core/src/test/kotlin/io/github/ticketing/e2e/BookingFlowE2ETests.kt` — 통합: 공연 등록부터 두 좌석 결제 확정까지 진행한다
- `core/src/test/kotlin/io/github/ticketing/e2e/PaymentFailurePathTests.kt` — 통합: 결제 실패는 예매를 취소하고 좌석을 반환한다; 같은 승인 웹훅은 한 번만 처리한다; 만료 후 승인되면 환불하고 좌석을 반환한다
- `core/src/test/kotlin/io/github/ticketing/e2e/SeatHoldConcurrencyTests.kt` — 통합: 같은 좌석을 동시에 선점하면 한 명만 성공하고 확정한다; 겹치는 다중 좌석 요청은 전부 선점하거나 전부 실패한다; Redis 선점을 우회해도 판매 좌석 유일 제약이 중복 확정을 막는다
- `core/src/test/kotlin/io/github/ticketing/e2e/support/FlowSupport.kt` — 지원 코드 또는 리소스: 사용처에 따라 분리
- `core/src/test/kotlin/io/github/ticketing/member/MemberAuthIntegrationTests.kt` — 통합 + 규칙 분기 단위 후보: 가입한 회원은 로그인해서 액세스 토큰으로 내 정보를 조회한다; 같은 이메일로 다시 가입하면 409와 코드를 돌려준다; 비밀번호가 틀리거나 없는 이메일이면 같은 오류로 401을 돌려준다; 토큰이 없거나 잘못되면 401이다; 리프레시 토큰으로 새 토큰을 받고 이전 리프레시 토큰은 다시 쓸 수 없다; 입력이 올바르지 않으면 400이다
- `core/src/test/kotlin/io/github/ticketing/member/SseTicketIntegrationTests.kt` — 통합: 인증된 회원은 유효 기간이 있는 일회용 티켓을 발급받는다; 인증 없이 티켓을 발급할 수 없다; 주최자가 두 번 발급하면 서로 다른 티켓을 받는다
- `core/src/test/kotlin/io/github/ticketing/notification/NotificationModuleTests.kt` — 통합: 확정 이벤트를 수신해 알림 로그를 출력한다; 취소 이벤트를 수신해 알림 로그를 출력한다
- `core/src/test/kotlin/io/github/ticketing/notification/service/BookingNotificationListenerTests.kt` — 단위: 확정 이벤트를 받으면 예매 식별자를 로그에 남긴다; 취소 이벤트를 받으면 예매 식별자와 취소 사유를 로그에 남긴다
- `core/src/test/kotlin/io/github/ticketing/payment/adapter/provided/PaymentListenerTests.kt` — 통합 + 규칙 분기 단위 후보: 결제 리스너는 트랜잭션 밖에서 PG를 호출하고 성공과 미확정을 레지스트리에 기록한다; 환불 리스너는 트랜잭션 밖에서 PG를 호출하고 접수와 거절을 레지스트리에 기록한다
- `core/src/test/kotlin/io/github/ticketing/payment/adapter/provided/PaymentWebhookHttpTests.kt` — 통합 + 규칙 분기 단위 후보: 정상 웹훅과 중복은 성공하고 계약 오류와 미등록 거래 기록은 롤백된다; 서명은 원문과 시간 허용 경계를 검증하고 파싱보다 먼저 수행된다; 서명된 잘못된 필드와 타입은 입력 오류다
- `core/src/test/kotlin/io/github/ticketing/payment/adapter/provided/batch/PaymentReconciliationJobTests.kt` — 통합: 일치와 모든 불일치를 기록하고 재실행 시 새 결과로 교체한다; 같은 주문의 여러 거래를 페이지 경계에서도 모두 비교한다; PG 조회 실패를 고친 뒤 같은 실행을 재시작한다
- `core/src/test/kotlin/io/github/ticketing/payment/adapter/required/jpa/PaymentPersistenceTests.kt` — 통합: 저장 조회와 상태 갱신은 모든 값을 복원한다; 주문 멱등 키 거래 ID는 각각 유일하다; 오래된 버전 저장은 거부된다; 웹훅 중복은 거부되고 롤백된 기록은 다시 저장된다
- `core/src/test/kotlin/io/github/ticketing/payment/adapter/required/pg/FakePgAdapterTests.kt` — 통합 + HTTP 모의 서버 기반 시나리오 단위 후보 (실제 로컬 HTTP 서버와 대기 시나리오는 통합 유지): 거래 조회는 날짜와 응답을 매핑한다; 거래 조회 오류와 잘못된 주문 번호는 예외로 전파한다; 결제 요청은 정수 금액과 멱등 키를 보내고 접수를 반환한다; 환불 요청은 본문 없이 같은 거래의 접수를 반환한다; 클라이언트 오류는 거절하고 서버 오류는 미확정으로 분류한다; 예상하지 않은 응답과 환불 거래 불일치는 미확정이다; 소수나 범위를 초과하는 금액은 손실 없이 변환할 수 없다; 실제 서버의 응답 지연과 연결 종료는 미확정이다; 설정 문자열에 웹훅 비밀이 드러나지 않는다
- `core/src/test/kotlin/io/github/ticketing/payment/application/PaymentApplicationTests.kt` — 통합 + 규칙 분기 단위 후보: 미확정 결제는 같은 키로 재시도하고 계약 충돌은 거부한다; 지연된 거절은 먼저 승인된 웹훅을 덮어쓰지 않는다; 웹훅 실패 기록은 롤백하고 동일 결과는 한 번만 발행한다; 환불 거절과 미확정은 같은 키로 복구하고 완료 후 재호출하지 않는다; 실패 웹훅의 잘못된 입력은 중복 기록을 롤백한다; 지연된 접수 응답과 웹훅 중복은 승인 상태를 유지한다; 이벤트 발행 실패는 상태와 웹훅 기록을 함께 롤백한다
- `core/src/test/kotlin/io/github/ticketing/payment/domain/PaymentTests.kt` — 단위: 승인과 환불은 중복 수신해도 최초 시각을 보존한다; 실패 재수신은 최초 사유와 시각을 보존한다; 허용되지 않은 모든 상태 전이는 거부된다; 금액 식별자 멱등 키와 거래 계약을 검증한다
- `core/src/test/kotlin/io/github/ticketing/payment/domain/ReconciliationRuleTests.kt` — 단위: 상태 대응표의 모든 쌍은 일치하고 나머지는 불일치한다; 없는 쪽과 복수 불일치를 판별하고 미확정 거래 번호는 비교하지 않는다
- `core/src/test/kotlin/io/github/ticketing/payment/flow/PaymentFlowTests.kt` — 통합 + 규칙 분기 단위 후보: 승인 웹훅은 결제와 예매를 확정하고 중복을 무시한다; 거절과 실패 웹훅은 예매를 취소하고 좌석을 반환한다; 미확정 결제는 원 발행 재제출로 같은 키를 재사용해 복구한다; 확정 취소와 만료 후 승인은 환불을 완료한다; 환불 거절과 미확정은 원 발행 재제출로 같은 키를 재사용한다; PG 응답 전에 도착한 웹훅은 거래를 연결하고 지연 응답도 상태를 유지한다; 같은 웹훅의 병렬 전달과 재전송은 하나의 확정으로 수렴한다; 병렬 결제 요청과 실패 발행 재제출은 하나의 거래와 결제로 수렴한다
- `core/src/test/kotlin/io/github/ticketing/settlement/SettlementModuleTests.kt` — 통합: 확정 이벤트의 값을 매출 사본에 기록한다; 확정 이벤트를 중복 수신해도 사본은 하나다; 환불이 필요한 취소는 확정 사본 없이도 기록한다; 환불이 필요 없는 취소는 기록하지 않는다
- `core/src/test/kotlin/io/github/ticketing/settlement/adapter/provided/batch/DailySettlementJobTests.kt` — 통합: KST 경계와 다른 날 확정된 환불을 정산하고 같은 날짜 재실행은 중복되지 않는다; 실패한 두 번째 청크는 같은 파라미터로 재시작한다; 대상 날짜가 없으면 실행하지 않는다
- `core/src/test/kotlin/io/github/ticketing/settlement/adapter/provided/web/SettlementApiIntegrationTests.kt` — 통합: 주최자는 기간 내 자기 정산만 날짜와 공연 순으로 조회한다; 일반 사용자는 403이고 인증이 없으면 401이다; 잘못된 기간과 날짜 또는 누락된 날짜는 400이다
- `core/src/test/kotlin/io/github/ticketing/settlement/domain/DailySettlementCalculatorTests.kt` — 단위: 수수료는 양수와 음수 모두 0 방향으로 절사한다; 수수료율은 0 이상 1 미만이어야 한다
- `core/src/test/resources/junit-platform.properties` — 지원 코드 또는 리소스: 사용처에 따라 분리
- `e2e/src/test/kotlin/io/github/ticketing/e2e/AdmissionGuardE2eTests.kt` — 별도 프로세스 E2E: 입장 토큰을 검증하고 유효한 토큰은 예매를 허용한다
- `e2e/src/test/kotlin/io/github/ticketing/e2e/BookingFlowE2eTests.kt` — 별도 프로세스 E2E: 대기열 입장부터 결제 확정까지 다른 관람객에게 좌석 변경이 전달된다
- `e2e/src/test/kotlin/io/github/ticketing/e2e/MultiInstanceE2eTests.kt` — 별도 프로세스 E2E: 두 인스턴스의 순번 입장 좌석 이벤트와 재연결 결과가 일치한다
- `e2e/src/test/kotlin/io/github/ticketing/e2e/support/E2eClient.kt` — 별도 프로세스 E2E
- `e2e/src/test/kotlin/io/github/ticketing/e2e/support/EventStream.kt` — 별도 프로세스 E2E
- `fake-pg/src/test/kotlin/io/github/ticketing/fakepg/FakePgApplicationTests.kt` — 통합: 컨텍스트가 뜬다
- `fake-pg/src/test/kotlin/io/github/ticketing/fakepg/payment/FakePgTestSupport.kt` — 지원 코드 또는 리소스: 사용처에 따라 분리
- `fake-pg/src/test/kotlin/io/github/ticketing/fakepg/payment/PaymentIdempotencyTest.kt` — 통합 + 규칙 분기 단위 후보: 같은 Idempotency-Key로 다시 요청하면 같은 transactionId를 돌려주고 웹훅은 한 번만 나간다; 같은 키에 본문이 다르면 422 IDEMPOTENCY_KEY_REUSED이다; 동시에 같은 키로 요청해도 거래는 하나만 만들어진다; Idempotency-Key 헤더가 없으면 400이다
- `fake-pg/src/test/kotlin/io/github/ticketing/fakepg/payment/PaymentScenarioTest.kt` — 통합 + 규칙 분기 단위 후보: SUCCESS는 202 PENDING을 돌려주고 승인 웹훅 1건을 보낸다; FAIL은 CARD_DECLINED 사유의 실패 웹훅을 보내고 거래는 FAILED가 된다; TIMEOUT은 응답만 timeout-delay만큼 늦고 결제는 정상 승인된다; DELAYED는 delayed-webhook-delay 동안 PENDING으로 남다가 승인 웹훅을 보낸다; DUPLICATE_WEBHOOK은 같은 eventId의 승인 웹훅을 duplicate-count만큼 더 보낸다; 웹훅 서명은 timestamp와 원문 본문으로 계산한 HMAC-SHA256 hex와 같다; 시나리오를 생략하면 확률이 0일 때 SUCCESS로 진행한다; 알 수 없는 시나리오나 잘못된 금액은 400이다
- `fake-pg/src/test/kotlin/io/github/ticketing/fakepg/payment/ProbabilityScenarioTest.kt` — 통합 + 규칙 분기 단위 후보: 시나리오를 생략하면 failure-rate 설정대로 실패 웹훅을 보낸다
- `fake-pg/src/test/kotlin/io/github/ticketing/fakepg/payment/RefundTest.kt` — 통합 + 규칙 분기 단위 후보: 승인된 거래를 환불하면 202이고 REFUND_COMPLETED 웹훅을 보낸다; 같은 키로 환불을 다시 요청해도 웹훅은 한 번만 나간다; 이미 환불된 거래를 다른 키로 환불하면 409이다; 승인되지 않은 거래는 환불할 수 없어 409이다; 없는 거래를 환불하면 404이다
- `fake-pg/src/test/kotlin/io/github/ticketing/fakepg/payment/ScenarioResolverTest.kt` — 단위: 요청에 시나리오가 있으면 확률과 무관하게 그것을 쓴다; 확률이 모두 0이면 SUCCESS이다; 난수가 확률 미만이면 해당 시나리오이고 FAIL, TIMEOUT, DUPLICATE_WEBHOOK 순으로 우선한다; 확률이 0과 1 범위를 벗어나면 검증에 실패한다
- `fake-pg/src/test/kotlin/io/github/ticketing/fakepg/payment/TransactionHistoryTest.kt` — 통합 + 규칙 분기 단위 후보: 거래 내역은 요청 시각의 한국 날짜로 필터링한다; UTC 전날 자정 이후 거래는 한국 날짜로만 조회된다; 거래가 없는 날짜는 빈 배열이다; date가 없거나 형식이 틀리면 400이다
- `fake-pg/src/test/kotlin/io/github/ticketing/fakepg/payment/WebhookRetryTest.kt` — 통합 + 규칙 분기 단위 후보: 수신 측이 2xx가 아니면 성공할 때까지 같은 이벤트를 재시도한다; 계속 실패하면 max-attempts에서 멈춘다
- `fake-pg/src/test/resources/config/application.yml` — 지원 코드 또는 리소스: 사용처에 따라 분리
- `fake-pg/src/test/resources/junit-platform.properties` — 지원 코드 또는 리소스: 사용처에 따라 분리
- `gateway/src/test/kotlin/io/github/ticketing/gateway/GatewayApplicationTests.kt` — 통합: 컨텍스트가 뜬다
- `gateway/src/test/kotlin/io/github/ticketing/gateway/GatewayIntegrationTestSupport.kt` — 지원 코드 또는 리소스: 사용처에 따라 분리
- `gateway/src/test/kotlin/io/github/ticketing/gateway/admission/AdmissionTokenFilterTests.kt` — 통합 + 규칙 분기 단위 후보: 일치하는 토큰은 재사용할 수 있고 본문을 그대로 전달한다; 토큰 누락과 무효 또는 불일치 시 거부한다; 유효하지 않거나 큰 본문은 거부한다; 인증 없이 생성할 수 없다; 결제와 조회에는 입장 토큰을 요구하지 않는다
- `gateway/src/test/kotlin/io/github/ticketing/gateway/ratelimit/RateLimitTests.kt` — 통합 + 규칙 분기 단위 후보: 예매 생성은 회원별 한도를 적용한다; 비로그인 요청은 전달된 클라이언트 IP별로 제한한다; 스트림 한도는 JWT 대신 소비한 티켓 회원으로 구분한다
- `gateway/src/test/kotlin/io/github/ticketing/gateway/route/GatewayRoutingTests.kt` — 통합: 코어 요청은 위조 회원 헤더 없이 전달된다; 인증된 대기열 요청은 검증한 회원 헤더로 전달된다; 대기열 요청에는 인증이 필요하다; 잘못된 서명과 만료된 토큰은 경로와 관계없이 거부한다; 유효한 토큰은 코어에 그대로 전달한다; 스트림 요청은 티켓 회원 헤더로 실시간 앱에 전달된다
- `gateway/src/test/kotlin/io/github/ticketing/gateway/sse/SseTicketFilterTests.kt` — 통합 + 규칙 분기 단위 후보: 티켓을 한 번 소비하고 회원과 재연결 정보를 전달한다; 티켓이 없거나 유효하지 않으면 거부한다; SSE 경로에만 CORS preflight를 허용한다
- `gateway/src/test/resources/junit-platform.properties` — 지원 코드 또는 리소스: 사용처에 따라 분리
- `realtime/src/test/kotlin/io/github/ticketing/realtime/RealtimeApplicationTests.kt` — 통합: 컨텍스트가 뜬다
- `realtime/src/test/kotlin/io/github/ticketing/realtime/RealtimeIntegrationTestSupport.kt` — 지원 코드 또는 리소스: 사용처에 따라 분리
- `realtime/src/test/kotlin/io/github/ticketing/realtime/multi/MultiInstanceConsistencyTests.kt` — 통합: 양쪽 인스턴스는 하나의 등록 순번을 공유한다; 동시 입장은 한 주기 배치 크기를 넘지 않고 회원마다 한 번만 입장한다; 한쪽에서 입장한 토큰을 다른 쪽 조회와 순번 스트림에서 확인한다; 좌석 변경은 양쪽에 같은 순서로 전달되고 다른 인스턴스에서 재생된다; 순번 스트림 연결을 끊어도 다른 인스턴스에서 같은 순번으로 재연결한다
- `realtime/src/test/kotlin/io/github/ticketing/realtime/multi/SecondInstance.kt` — 지원 코드 또는 리소스: 사용처에 따라 분리
- `realtime/src/test/kotlin/io/github/ticketing/realtime/queue/AdmissionServiceTests.kt` — 통합 + 규칙 분기 단위 후보: 앞의 두 명만 입장하고 토큰과 남은 순번을 조회한다; 유예가 지난 회원을 제거하고 살아 있는 회원을 입장시킨다; 모든 활성 회차를 처리한다
- `realtime/src/test/kotlin/io/github/ticketing/realtime/queue/QueueApiTests.kt` — 통합 + 규칙 분기 단위 후보: 등록 순서대로 순번을 부여하고 재등록하면 유지한다; 상태 조회는 순번과 마지막 신호를 갱신한다; 유효한 입장 토큰을 반환하고 없는 토큰은 정리한다; 이탈은 멱등이고 뒤에 있던 순번이 당겨진다; 회차가 없거나 회원 헤더가 유효하지 않으면 오류를 반환한다
- `realtime/src/test/kotlin/io/github/ticketing/realtime/queue/QueuePositionStreamTests.kt` — 통합 + 규칙 분기 단위 후보: 순번 변화와 입장 이벤트를 보내고 종료한다; heartbeat는 신호를 갱신하며 연결 취소는 대기열을 유지한다; 이탈하면 removed를 보내고 종료한다; 등록하지 않은 회원과 헤더 누락 및 없는 회차는 HTTP 오류다
- `realtime/src/test/kotlin/io/github/ticketing/realtime/seat/SeatStatusStreamTests.kt` — 통합 + 규칙 분기 단위 후보: 스냅샷과 두 구독자의 실시간 좌석 변경 및 heartbeat를 보낸다; 마지막 이벤트 이후를 재생하고 잘린 ID와 잘못된 ID는 스냅샷을 보낸다; 빈 스트림 스냅샷 이후 연속 변경은 순서대로 한 번씩 전달된다; 처음부터 남은 스트림은 0부터 재생하고 잘린 스트림은 스냅샷을 보낸다; 마지막 구독이 끝나면 재구독은 새 리더로 시작한다; 회원 헤더 누락과 없는 회차는 스트림 시작 전 오류다
- `realtime/src/test/resources/junit-platform.properties` — 지원 코드 또는 리소스: 사용처에 따라 분리

## mock 위치

- `core/src/test/kotlin/io/github/ticketing/booking/adapter/provided/web/BookingApiIntegrationTests.kt`: `PaymentRequestListener`, `RefundRequestListener` 빈 대체.
- `core/src/test/kotlin/io/github/ticketing/booking/application/flow/BookingPaymentFlowTests.kt`: 결제 및 환불 리스너 빈 대체.
- `core/src/test/kotlin/io/github/ticketing/payment/adapter/provided/PaymentWebhookHttpTests.kt`: 이벤트 리스너 빈 대체. Spring MockMvc 자체는 Mockito mock과 다르다.

## 저장소별 호출 메서드

운영 코드 호출과 기존 저장소 통합 테스트가 직접 호출하는 메서드를 함께 보존한다. 메서드의 잠금·JPQL·flush 의미와 반환 타입은 기존 선언과 호출을 기준으로 유지한다.

| 저장소 | 운영 코드에서 사용 | 기존 테스트에서 추가 사용 |
|---|---|---|
| `MemberRepository` | `findByEmail`, `existsByEmail`, `findById`, `saveAndFlush` | 없음 |
| `RefreshTokenRepository` | `findByTokenHash` (비관적 쓰기 잠금), `revokeAllByMemberId`, `save` | 없음 |
| `VenueRepository` | `existsByName`, `findById`, `save` | `flush` |
| `PerformanceRepository` | `findById`, `save` | 없음 |
| `ScheduleRepository` | `existsByPerformanceIdAndStartsAt`, `saveAndFlush` | `save`, `findById` |
| `BookingJpaRepository` | `findWithSeatsById`, `findWithSeatsByMemberIdAndIdempotencyKey`, `saveAndFlush` | 없음 |
| `ScheduleSeatCopyJpaRepository` | `findByKeyScheduleIdAndKeySeatIdIn`, `saveAll` | 없음 |
| `PaymentJpaRepository` | `findByOrderId`, `findByIdempotencyKey`, `findByPgTransactionId`, `findById`, `saveAndFlush` | 없음 |

검색 범위는 core의 운영 코드와 기존 테스트다. `findById`의 `Optional`, `saveAll`의 결과 타입 및 Spring Data 기본 구현과 호환되는 선언은 구현 시 검증한다.


## 호출 근거와 보존할 영속성 계약

- 회원 저장소: `core/src/main/kotlin/io/github/ticketing/member/service/MemberService.kt`와 `core/src/main/kotlin/io/github/ticketing/member/service/AuthService.kt`. 중복 가입의 즉시 flush와 인증 갱신 시 잠금·일괄 폐기를 보존한다.
- 카탈로그 저장소: `core/src/main/kotlin/io/github/ticketing/catalog/service/CatalogRegistrationService.kt`, `core/src/main/kotlin/io/github/ticketing/catalog/seed/CatalogSeedRunner.kt`, `core/src/test/kotlin/io/github/ticketing/catalog/CatalogRepositoryTests.kt`. 중복 회차의 즉시 flush와 테스트의 명시적 flush를 보존한다.
- 예매 저장소: `core/src/main/kotlin/io/github/ticketing/booking/adapter/required/jpa/BookingPersistenceAdapter.kt`, `core/src/main/kotlin/io/github/ticketing/booking/adapter/required/jpa/ScheduleSeatPersistenceAdapter.kt`. 좌석 포함 조회와 저장 시 flush, 좌석 사본의 신규 판별을 보존한다.
- 결제 저장소: `core/src/main/kotlin/io/github/ticketing/payment/adapter/required/jpa/PaymentPersistenceAdapter.kt`. 조회 세 종류와 저장 시 flush 및 버전 충돌을 보존한다.

DB 유니크 제약·낙관적 및 비관적 잠금·실제 이벤트 전달과 롤백, Redis Lua 원자성·TTL, 인증, PG HTTP, 배치 재실행·재시작, 다중 인스턴스 SSE는 해당 통합 또는 E2E 시나리오에 계속 남긴다. 단위 fake의 성공은 이 보장을 대체하지 않는다.

## 기존 성능 기록

[통합 테스트 재사용 개선](../test-performance.md)의 2026-11-11 core 단독 측정은 테스트 161개, 테스트 스위트 시간 합계 67.131초 → 52.752초, Spring 컨텍스트 9 → 7회, PostgreSQL과 Redis 컨테이너 각각 9 → 1회다. 각 1회 측정이며 스위트 시간 합계는 JVM 전체 시간이나 Gradle 전체 시간이 아니다. Ryuk과 jOOQ 생성 컨테이너도 기동 수에서 제외한다. 이번 전체 모듈 기준선과 단위 테스트 JVM 시간은 별도 측정이 필요하다.
