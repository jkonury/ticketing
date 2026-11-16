# hot-seats 1,000명 콜드 p95 900ms: 기동 시 내부 예열 계획

- 작성일: 2026-11-16
- 상태: 3.1 대조·콜드 진단 후 3.2를 구현했고, 사용자 요청으로 [HTTP 예열 15회 × 1,000건으로 확대](../performance/m5/16-startup-warmup-burst-volume.md)([ADR 0030](../adr/0030-startup-warmup-burst-volume.md)), [실제 예매 핸들러 경유](../performance/m5/17-warmup-through-real-handler.md)([ADR 0031](../adr/0031-warmup-through-real-booking-handler.md)), [예매 구간 트래픽 혼합](../performance/m5/18-warmup-traffic-mix.md)([ADR 0032](../adr/0032-warmup-traffic-mix.md))으로 이어졌다. 현재 코드(18)의 JFR OFF 독립 3회 전체·성공 p95는 760·772·750ms / 677·707·707ms로 900ms 잠정 기준을 모두 충족하고 300ms 장기 목표는 미달이다. 16의 741·843·853ms도 충족했으나 17의 2회차 1,458ms는 900ms 미달이었다. 기능 검사는 통과했지만 3.2 JFR 완료 기준(외부 예열 수준 Interpreted 비율)은 최신 코드로 재집계하지 않았고 16 시점 core 23.1%는 미달이다. 순수 콜드 회귀는 사용자 요청으로 생략했다. [TCP·연결 진단](../performance/m5/19-warmup-tcp-diagnosis.md)은 외부 3회차 중단으로 미완료다. 최초 구현 결과는 [이전 검증 보고](../performance/m5/15-layered-startup-warmup.md)에 보존한다.
- 목표(잠정, 2026-11-16 전환): 앱을 새로 기동하고 readiness 전 내부 예열만 거친 뒤(외부 워밍업 요청 없음) 1,000명 hot-seats 예매 생성의 전체·성공 p95를 각각 < 900ms로 독립 3회 모두 달성한다. 300ms는 장기 목표로 남긴다. 전환 근거는 [문서 16의 목표 전환](../performance/m5/16-startup-warmup-burst-volume.md#목표-전환)이다. 달성이 보장되지 않으며 미달이면 결과를 그대로 남긴다.
- 실행 도구: Gatling 러너(`--booking-p95-ms 900`, `verdict.json`). 기존 실행의 `verdict.json`은 300ms 판정이며 900ms 재판정은 문서에서만 한다.
- 관련 문서: [Gatling 최종 보고](../performance/m5/12-gatling-hot-seats-p95-300ms-report.md), [Gatling 계획](./hot-seats-gatling-p95-300ms.md), [Step 8 최종 보고](../performance/m5/11-step8-final-report.md), [ADR 0025](../adr/0025-booking-seat-copy-cache.md), [ADR 0026](../adr/0026-aot-cache-startup.md).

## 1. 근거와 한계

| 근거 | 수치 | 해석 |
|---|---|---|
| 순수 콜드 B (k6, Scouter OFF) | 중앙값 1,385ms | 회귀 기준(1,500ms)만 통과 |
| 외부 15회 예열 (Gatling) | 전체 p95 350~386ms(D 중앙값 386ms) | **내부 예열의 비교 기준이자 사실상 상한 추정치.** 300ms 장기 목표는 미달, 900ms 잠정 기준은 충족 |
| 폭주 1초 평균 CPU | gateway 1.25·core 1.03코어 | 평균은 비어 있어 sub-second 포화는 판단 불가 |
| 미해결 | 클라이언트~Gateway 118ms, 1.2초 튐, 연결 리셋 | 예열로 사라지는지 알 수 없음 |

- 외부 15회 예열로도 300ms에 닿지 못했으므로 내부 예열이 그보다 나을 근거는 없다. 내부 예열이 해결하는 것은 JIT·클래스 초기화 비용이며, 남은 병목이 다른 곳(대기·연결)이라면 목표는 달성되지 않는다.
- 따라서 이 계획의 첫 단계는 구현이 아니라 **예열 후 병목이 JIT인지 확인하는 대조**다. 대조에서 이득이 외부 예열 대비 의미 없으면 구현 단계로 넘어가지 않고 병목 진단 계획으로 돌아간다.

## 2. 범위

- 포함: JVM 컴파일 임계 대조, 계층형 내부 예열, 러너의 JVM 옵션 전달, 측정·보고.
- 제외: AOT 적용 변경, Redis 멱등 저장, 인스턴스 증설, 풀 크기 변경(근거 없음), 외부 워밍업 요청.
- 서비스 동작(readiness 시점·기동 시간, 예열용 DB·Redis 쓰기)이 바뀌는 구현은 이 문서 합의 후에 시작한다.

## 3. 단계와 중단 조건

### 3.1 컴파일 임계 대조 (코드 변경 없음)

- 러너에 core·gateway JVM 옵션 전달 수단이 없으면 추가한다(`scripts/loadtest/run.sh`). 측정 도구 변경이며 서비스 코드는 바꾸지 않는다.
- `-XX:CompileThresholdScaling` 1.0(기준)·0.25·0.1, `-XX:CICompilerCount`를 조합해 순수 콜드 B Gatling 3회씩. 기본값은 `java -XX:+PrintFlagsFinal`로 확인한다.
- 기록: p95 변화 폭, 폭주 구간 JFR Interpreted 비율.
- **중단 조건**: 최선 설정의 중앙값이 1,385ms 대비 의미 있게 줄지 않거나 외부 예열 계열(350~386ms)에 근접하지 못하면 내부 예열의 기대 효과가 작다고 보고 보고서를 남기고 멈춘다.

### 3.1.1 중단 이후 추가 진단 (2026-11-16 합의)

- 사용자가 선택한 후속 작업은 **JVM 옵션 실제 적용 확인 + JFR 콜드 병목 진단**이다. 내부 예열 구현 재개는 포함하지 않는다.
- 기준 1.0과 낮춘 값 0.1을 각각 새 JVM에서 실행하고 `PrintFlagsFinal` 로그로 core·gateway의 실제 `CompileThresholdScaling`·`CICompilerCount`를 확인한다.
- 기존과 같은 Gatling 1,000명·외부 예열 없음·Scouter OFF·관측 ON·CPU 배정으로 JFR을 수집한다. 각 설정 1회 진단이며, 기존 JFR OFF 3회 판정 계열과 합치지 않는다.
- Gatling 원시 로그의 예매 요청 시작~마지막 응답 구간을 JFR 시각과 맞추고, `ExecutionSample`의 최상위 Java 프레임이 Interpreted인 표본 비율과 초기화·연결 관련 스택을 기록한다. 전체 프레임 비율은 보조 지표로만 쓴다.
- 기존 외부 15회 예열 JFR도 같은 방식으로 집계한다. 표본·스택은 요청별 인과나 지연 기여율을 증명하지 않으며, 기본 profile에서 수집하지 않는 이벤트의 부재를 비용 0으로 해석하지 않는다.
- 결과와 남은 진단 한계를 보고한 뒤 3.2 구현 여부는 다시 결정한다.

### 3.2 계층형 내부 예열 (2026-11-16 구현 승인)

- 계층 1(공유 자원 0, 많이 반복): Gateway JWT 검증·필터 파싱·Jackson, core 요청 역직렬화·Bean Validation·`Booking` 도메인 규칙·응답 직렬화, jOOQ 렌더링만, Lettuce 명령 인코딩.
- 계층 2(실제 어댑터, 격리, 적게 반복): 실제 `CreateBookingService.create()` 호출. Redis는 예열 전용 회차 네임스페이스·짧은 TTL·종료 후 삭제, 좌석 사본은 Redis 캐시 경로(ADR 0025), persist는 rollback-only 트랜잭션, 승자·패자(409)를 실제 비율에 가깝게 섞고 동시성 2~4·초당 상한을 둔다. stub 포트로 예열하지 않는다(타입 프로파일 오염으로 deopt).
- Gateway 계층 2는 최초 8회 호출에서 사용자 요청에 따라 기본 15회 × 1,000건으로 확대했다([ADR 0030](../adr/0030-startup-warmup-burst-volume.md)). core ready 뒤 Gateway 운영 라우트·실제 `BookingController`·인가를 경유하며([ADR 0031](../adr/0031-warmup-through-real-booking-handler.md)), 예매 생성·결제 요청·상세 조회·웹훅·PG 송신을 실제 구간 구성으로 섞는다([ADR 0032](../adr/0032-warmup-traffic-mix.md)). 예열 전용 컨트롤러는 삭제했고 `@Primary` 데코레이터가 예열 principal만 격리 환경으로 보낸다.
- readiness: `ApplicationRunner`에서 예열하고 시간 상한을 넘기면 중단하고 ready로 전환한다.
- 구현 전 확인할 가정: Spring Boot 4.x의 `ApplicationRunner`·readiness 전환 순서(공식 문서), rollback-only persist의 Modulith 이벤트 비발행, `SeatHoldPort` 어댑터가 realtime으로 좌석 이벤트를 흘리지 않는지, JDK 25 AOT 캐시가 기계어를 캐시하지 않는다는 가정(JEP 확인).
- 완료 기준: 폭주 구간 JFR Interpreted 비율이 외부 15회 예열 수준으로 내려가고, 예열 중 DB·Redis 호출 수와 소요 시간이 `env.txt`에 기록된다.

### 3.3 검증

- 예열 포함 기동 3회의 전체·성공 예매 p95가 3회 모두 900ms 미만인지 본다(2026-11-16 전환, 300ms는 장기 목표). 정합성(중복 판매 0·불일치 0)·오류율·SSE 성공률을 함께 본다.
- 순수 콜드 B를 회귀 기준(1,500ms)으로 함께 잰다. 예열 비활성 설정으로 순수 콜드를 재현할 수 있어야 한다.
- 기동 시간 증가와 예열 상한 동작을 기록한다. 계열을 섞지 않는다.
- 결과는 [내부 예열 구현·검증 보고](../performance/m5/15-layered-startup-warmup.md)에 남기고 허브·로드맵에 연결한다. 미달이면 체크하지 않는다.

## 4. 측정 규칙

- 다른 Gradle 검사와 동시에 실행하지 않는다. 실패·무효 실행은 보존한다.
- Gatling 외부 예열 계열, 컴파일 임계 대조 계열, 내부 예열 계열, 순수 콜드 계열을 별도 표로 기록한다.
- 서비스 코드 변경 전후는 같은 조건에서 비교하고 좋은 회차만 골라 합격시키지 않는다.

## 5. 확정한 구현 조건

- 최초 구현의 3.2 검증 후 사용자 요청으로 [ADR 0030](../adr/0030-startup-warmup-burst-volume.md)을 적용했다. 실제 HTTP 경유를 기본 15회 × 1,000건·동시 요청 상한 1,000으로 확대했다. [확대 검증 보고](../performance/m5/16-startup-warmup-burst-volume.md)에 별도 기록하며 순수 콜드 회귀 생략 요청은 유지한다.
- 2026-11-16 사용자가 중단 이후에도 **3.2 계층형 내부 예열 구현**을 명시적으로 요청했다. DB 롤백·Redis 격리를 포함한 해당 범위의 구현을 진행한다.
- 기동 예열 상한은 core 기본 30초·Gateway 기본 120초(core 대기 포함), 설정 가능 범위는 0초 초과~2분이다. 상한·실패 시 새 작업을 중단하고 취소한 뒤 ready로 전환한다. 이미 발송된 I/O의 정리는 TTL·롤백으로 보장하며 timeout 호출 수는 취소 시점 스냅샷이다. 부하 러너는 예열 미완료 시 본 측정을 거부한다.
- 기본 비활성. core는 메모리 10,000회·실제 200회(동시성 2·승자 5%·패자 95%·작업자별 50ms 대기), Gateway는 메모리 10,000회·core ready 뒤 실제 경유 15회 × 1,000건(승자 5%·동시 요청 상한 1,000)이다.
- 예열 Redis 키는 음수 회차별 `booking:warmup:` 아래에서 생성 즉시 30초 TTL을 갖고 종료 시 삭제한다. DB에는 예열 회차 원장을 만들지 않으며 사본 캐시 경로로 읽고 예매 저장은 실제 JPA flush 후 롤백한다.
- 경로는 생성 후 3분·최대 30,000회·동시 1,000개로 제한하며 서버 역할을 요구한다. 17에서 별도 예열 경로 대신 운영 `POST /api/v1/bookings`를 쓰며, 서버 역할 JWT·기동 시 비밀 자격·전용 보안 체인·고정 템플릿 제한으로 보호한다([ADR 0031](../adr/0031-warmup-through-real-booking-handler.md)). 자세한 격리·관측·취소 정책은 [ADR 0029](../adr/0029-layered-startup-warmup.md)와 [ADR 0030](../adr/0030-startup-warmup-burst-volume.md)을 따른다. 300ms 목표 체크는 측정 통과 전까지 유지한다.
