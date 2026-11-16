# 0031. 내부 예열을 실제 예매 핸들러와 보안 체인 경유로 변경

## 상태

수락 (2026-11-16). 사용자가 [ADR 0030](0030-startup-warmup-burst-volume.md) 이후에도 JFR OFF p95가 741·843·853ms로 남은 상황에서, 예열이 별도 컨트롤러가 아닌 실제 `BookingController`·Security 체인을 거치도록 요청했다. ADR 0029·0030의 예열 전용 경로(`/internal/startup-warmup`)와 서버 JWT 역할만으로 호출하는 방식을 대체한다. 데이터 격리 정책(음수 회차·전용 Redis 키·TTL·DB 롤백·호출 상한)은 유지한다.

확장 (2026-11-16): [ADR 0032](0032-warmup-traffic-mix.md)가 전용 체인의 허용 경로를 결제 요청·상세 조회 고정 템플릿으로 넓히고 예매 생성 좌석 수를 1~4로 늘렸다. 아래 "허용 경로는 `POST /api/v1/bookings` 하나", "고정 템플릿(회차 1·좌석 [1])"은 ADR 0032의 세 경로·좌석 `[1..n]` 템플릿으로 바뀌었고 나머지 보안 경계는 그대로다.

## 맥락

ADR 0030 이후 JFR(`build/loadtest/runs/20261116-132736-hot-seats/1/core.jfr`)의 예매 구간에서 `jdk.Deoptimization`을 집계했다. core 198건(외부 예열 `20261115-221926-hot-seats/1`은 1건), Gateway 46건(외부 0건)이다. 사유는 class_check 65·bimorphic_or_optimized_type_check 62·unstable_if 47·unreached 16이며 상위 메서드는 `AopUtils.invokeJoinpointUsingReflection`, Kotlin `ReflectJvmMapping.getJavaMethod`, `AnnotatedMethodParameter.getParameterAnnotations`, Jackson 직렬화, Micrometer 핸들러다. 예열은 `BookingWarmupController`로, 본 요청은 `BookingController`로 들어와 공통 프레임워크 호출 지점의 타입 프로파일이 예열 전용 호출자로 굳었다가 본 부하에서 깨졌을 가능성이 있다. 이 인과는 확정이 아니며 이번 변경으로 검증한다.

## 검토한 대안

- 현행 유지(별도 예열 컨트롤러): 위 프로파일 차이가 남는다.
- 예열 principal에 USER 역할 JWT만 발급: JWT 비밀은 운영 토큰 발급과 같은 신뢰 루트이고 기동 시점·기간 제한이 없다. 실제 데이터 접근은 컨트롤러 이후 분기에 전적으로 의존한다.
- 실제 컨트롤러에 예열 분기를 직접 추가: 운영 핸들러 코드에 예열 조건이 섞인다.
- **기동 시 비밀 자격 + 컨트롤러 아래 가장자리 분기(선택)**.

## 결정

### 요청 경로

- Gateway가 자신의 포트로 `POST /api/v1/bookings`를 호출한다. 운영 라우트 `core-bookings-create`, 운영 `JwtAuthenticationFilter`·`requireBookingMember`·`AdmissionTokenFilter`(실제 Redis 입장 토큰 조회·본문 캐시·회차 대조)를 그대로 거치고 core의 실제 `BookingController.create`·`@Valid` 바인딩·`@PreAuthorize("hasRole('USER')")`·`ApiExceptionHandler`가 응답한다. 예열 전용 컨트롤러·Gateway 라우트(`/internal/startup-warmup`)는 삭제했다.
- 승자·패자는 멱등 키(`startup-warmup:winner`, `startup-warmup:conflict`)로 구분한다. 승자는 201, 패자는 실제 예외 처리 경로의 409 `SEAT_ALREADY_HELD`다.

### 가장자리 분기

- `CreateBookingUseCase`의 `@Primary` 데코레이터(`WarmupAwareCreateBooking`, 예열 활성 시에만 생성)가 예열 principal일 때만 음수 회차 격리 환경(`BookingWarmupWork.create`)으로 보낸다. 컨트롤러·실제 서비스·운영 공통 경로에는 분기가 없다. 예열이 아닌 요청은 `createBookingService`를 그대로 호출한다.
- 격리 정책은 그대로다: 서버가 만든 음수 회차·회원 ID, `booking:warmup:` 접두 Redis 키, 30초 TTL과 `finally` 삭제, 승자는 `REQUIRES_NEW` 트랜잭션 rollback-only.
- 예열 principal이라도 요청이 고정 템플릿(회차 1·좌석 [1]·두 멱등 키 중 하나)이 아니면 실제 유스케이스를 호출하지 않고 403으로 거절한다. 양수 회차·좌석·회원 ID는 예열 경로에서 DB·Redis로 전달되지 않는다.
- DB·Redis 호출 수는 응답 헤더 `X-Startup-Warmup-Io`로 돌려주고 Gateway가 집계한다(데코레이터가 예열 요청에만 설정).

### 보안 경계

예열 principal에 USER 권한을 주는 것은 보안 경계 변경이므로 다음을 모두 요구한다.

1. **서버 역할 JWT(`STARTUP_WARMUP`)**: 기존과 같이 Gateway가 JWT 비밀로 120초 토큰을 만든다. 일반 회원에게 발급하지 않는다.
2. **기동 시 비밀 자격**: core가 기동 시 `SecureRandom` 256비트 값을 만들어 메모리에만 둔다. 로그·저장소·환경 변수에 두지 않는다. Gateway는 서버 역할 JWT로 `POST /internal/startup-warmup/credential`을 호출해 **한 번만** 받으며(재호출은 409) 메모리에만 보관한다. 자격은 core 기동 후 3분, readiness가 `ACCEPTING_TRAFFIC`일 때만 유효하고 최대 30,000건·동시 1,000개로 제한한다.
3. **전용 보안 체인**: 요청에 `X-Startup-Warmup-Key` 헤더가 있으면 별도 `SecurityFilterChain`(`@Order(HIGHEST_PRECEDENCE)`)이 처리한다. 허용 경로는 `POST /api/v1/bookings` 하나이며 나머지는 `denyAll`이다. 체인은 운영과 같은 JWT 리소스 서버 필터를 쓰고, 그 뒤 필터가 (서버 역할 JWT) AND (헤더 비밀 일치)일 때만 `ROLE_USER` 권한의 예열 principal(이름 `-1`)로 바꾼다. 하나라도 어긋나면 401/403이다. 헤더 없는 운영 요청은 기존 체인을 그대로 탄다.
4. **Gateway 차단**: 항상 최우선으로 실행되는 `StartupWarmupIngressFilter`가 외부 요청의 `X-Startup-Warmup-Key`를 Gateway가 보유한 자격과 일치하지 않으면 제거한다. 예열을 끈 경우·기간(설치 후 3분) 후에는 모든 헤더를 제거한다. `/internal/**` 라우트를 삭제해 외부에서 자격 발급 경로에 닿을 수 없다. 자격 발급은 Gateway가 core 주소로 직접 호출한다.
5. **입장 토큰**: Gateway가 `queue:admission:warmup-<UUID>`(회원 -1·회차 1, TTL 3분)를 만들어 쓰고 종료 시 삭제한다. 키가 무작위이고 회원 ID가 음수라 실제 회원의 요청과 일치하지 않는다. ADR 0030의 "예열용 입장 토큰 Redis 쓰기 없음"을 이 한 가지로 바꾼다. realtime은 이 키를 대기열 목록에서 참조하지 않는다.

위 5가지가 모두 깨져도 예열 principal이 닿는 것은 고정 템플릿이 만드는 음수 회차 격리 환경뿐이다. 실제 회원·회차·좌석 데이터를 읽거나 쓰는 경로는 없다.

### 한계(운영 코드의 예열 분기)

- `RateLimitFilter`는 Gateway가 일치시킨 예열 요청 속성이 있으면 한도 검사를 건너뛴다. 15,000건이 회원 -1의 버킷을 소진하지 않게 하기 위한 유일한 운영 필터 분기이며 Redis rate limiter 경로는 예열에서 실행되지 않는다.
- 전용 체인은 운영 체인과 같은 필터 클래스를 쓰지만 별도 체인 인스턴스다. `member` 모듈의 `SecurityConfig`를 `booking`이 참조하지 않기 위한 선택이다(모듈 간 직접 참조 금지).
- 예열 요청도 Micrometer 지표에 기록된다(경로 `/api/v1/bookings`). 이전에도 예열은 서비스 단계 지표를 올렸다.
- 결제·예매 조회·대기열·SSE 요청은 추가하지 않았다. AOT·운영 풀·컴파일 옵션·인스턴스 수는 변경하지 않았다.

## 결과와 검증

[결과 보고](../performance/m5/17-warmup-through-real-handler.md)에 기록한다. 인과(프로파일 공유)는 탈최적화 건수와 p95 변화로만 판단하며 실패하면 그대로 기록한다.
