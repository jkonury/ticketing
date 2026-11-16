# 0029. 선택 가능한 계층형 내부 예열과 자원 격리

## 상태

일부 대체됨 ([ADR 0030](0030-startup-warmup-burst-volume.md), 2026-11-16): Gateway 호출량·시간 상한·경로 제한을 확대했다. 아래는 최초 결정 기록이다. 사용자가 컴파일 임계 대조·JFR 진단 이후에도 [계획 3.2](../plans/hot-seats-internal-warmup-cold-300ms.md)의 구현을 명시적으로 요청했다. p95 300ms 달성 여부와 분리한 구현 결정이다.

## 맥락과 대안

[콜드 JFR 진단](../performance/m5/14-cold-jfr-diagnosis.md)에서는 core Interpreted 표본과 클래스 로딩 경합·연결 대기가 함께 남았다. 컴파일 임계 변경만으로 외부 예열 상태에 도달하지 못했으나 내부 예열의 효과도 부정되지 않았다.

- 외부 예열만 사용: 순수 콜드와 구분은 쉽지만 readiness 전 앱 준비라는 요청을 충족하지 않는다.
- 모든 요청을 fake 포트로 예열: 실제 DB·Redis 어댑터 및 호출 타입이 달라 채택하지 않는다.
- 모든 예열을 실제 어댑터로 반복: 공유 자원 비용과 부작용이 커 채택하지 않는다.
- 메모리 연산 반복 + 소수의 격리된 실제 호출을 결합한다.

## 결정

- 기본은 비활성이다. `STARTUP_WARMUP_ENABLED=true`로 core·gateway의 예열을 켜고 `STARTUP_WARMUP_MAX_DURATION`으로 각 앱의 상한을 설정한다(기본 30초, 최대 2분). 비활성 설정으로 순수 콜드 계열을 재현한다.
- core는 `ApplicationRunner`에서 두 Virtual Thread로 메모리 연산을 반복한 뒤 실제 `CreateBookingUseCase.create()`를 호출한다. 기본 메모리 연산 10,000회, 실제 호출 200회, 승자 5%·패자 95%, 동시성 2, 작업자별 호출 뒤 50ms 대기로 합계 초당 최대 약 40회다. 조회·쓰기 모두 실제 어댑터를 사용한다.
- 실제 호출은 매번 UUID에서 만든 음수 회차·회원 ID를 쓴다. 외부 예매 요청의 회차 ID는 양수로 검증한다. 예열용 회차·좌석 원장은 DB에 만들지 않고 불변 좌석 사본을 예열 Redis 캐시에만 넣는다. 실제 예매 저장은 별도의 rollback-only 트랜잭션에 참여시킨다. 자동 생성 ID의 시퀀스 증가는 롤백되지 않는다.
- 음수 회차의 Redis 키는 `booking:warmup:` 접두사 아래에 둔다. 일반 `booking:seat-events:`와 달라 realtime이 소비하는 SSE Stream에 섞이지 않는다. 사본 캐시·선점·스냅샷·Stream은 생성 명령/스크립트 안에서 30초 TTL을 적용하고 `finally`에서 명시적으로 삭제한다.
- Gateway 메모리 예열은 실제 JWT 디코더, 운영 필터가 공유하는 Bearer·본문 파서와 Jackson을 호출한다. 이후 core readiness를 기다리고 자신의 HTTP 서버를 통해 전용 경로를 8회 호출해 실제 GlobalFilter·Gateway 라우팅·HTTP 연결 풀도 거친다(승자 1회·패자 7회, 순차·50ms 간격).
- 전용 POST 경로 `/internal/startup-warmup`은 예열 활성화 시에만 생성한다. core는 `STARTUP_WARMUP` JWT 역할을 요구하고, 생성 후 2분·최대 8회·동시성 2로 제한한다. 일반 회원에게 이 역할을 발급하지 않는다. Gateway가 JWT 비밀 키로 짧은 서버 토큰을 만들며 브라우저나 로그에 노출하지 않는다. 예열 요청의 데이터 식별자는 외부에서 받지 않는다.
- 두 앱 모두 runner 완료 뒤 Boot의 readiness 전환을 따른다. 러너와 E2E는 단순 health 대신 core·gateway의 `/actuator/health/readiness`를 기다린다. Gateway runner의 기동 스레드만 제한 시간 동안 Future를 기다리며, JWT 검증·HTTP·재시도·지연은 Reactor 비동기 연산이다. 요청 처리 이벤트 루프에서 블로킹하지 않는다.
- 상한·실패 시 새 작업을 중단하고 진행 중 작업을 취소한 뒤 기동을 계속한다. 취소는 이미 전송한 DB/Redis 명령을 강제로 되돌릴 수 없다. 그런 명령도 예열 네임스페이스·짧은 TTL·무조건 DB 롤백 안에 한정되며, 정리 실패에 대비해 TTL을 최종 방어선으로 둔다. timeout 로그의 호출 수는 취소 시점 스냅샷이다.
- 예열 활성화 시에만 DataSource를 감싸 예열 스레드의 JDBC execute 호출(배치 실행은 1회)을 센다. Redis는 예열 스레드의 템플릿 명령 API 호출을 센다(Lua 실행 1회, 내부 명령·드라이버 재전송 제외). Gateway의 원격 호출 결과에는 core에서 센 수가 포함된다. 러너는 완료 상태·소요 시간·반복 수·호출 수를 `env.txt`에 옮긴다.

## 확인 근거와 결과

- [Spring Boot 4.1.2 기동 순서](https://docs.spring.io/spring-boot/reference/features/spring-application.html): ApplicationRunner 완료 후 ApplicationReadyEvent와 ACCEPTING_TRAFFIC 전환이 일어난다.
- [Spring 트랜잭션 문서](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html): TransactionTemplate 콜백에서 rollback-only를 설정한다. 현재 CreateBookingService의 생성 경로는 Modulith 이벤트를 발행하지 않는다. 결제·확정 유스케이스는 예열하지 않는다.
- [JDK 25 AOT 문서](https://docs.oracle.com/en/java/javase/25/vm/ahead-time-class-loading-and-linking.html): AOT 클래스 로딩·링킹과 실행 코드 예열은 별개다. 기존 AOT 설정은 변경하지 않는다.
- [Redis 스크립트 원자성](https://redis.io/docs/latest/develop/programmability/): 생성과 만료를 한 스크립트에 묶는다.

기동 비용이 늘고 예열 경로·설정·관측 코드가 추가된다. 내부 예열·순수 콜드·외부 예열·JFR 진단 계열을 섞지 않는다. [구현·검증 보고](../performance/m5/15-layered-startup-warmup.md)에서 기능 검사는 통과했지만 core Interpreted 31.8%와 JFR OFF 3회 p95 1,505·1,448·1,242ms로 외부 예열 수준·300ms 목표에 미달했다. 순수 콜드 회귀는 사용자 요청으로 생략했다.
