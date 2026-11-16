# hot-seats 1,000명 콜드 p95 300ms: 기동 시 내부 예열 계획

- 작성일: 2026-11-16
- 상태: 3.1 컴파일 임계 대조 완료, **중단 조건에 해당**([결과](../performance/m5/13-compile-threshold-control.md)). 3.2 구현은 사용자 결정 전까지 시작하지 않는다.
- 목표(조건부): 앱을 새로 기동하고 readiness 전 내부 예열만 거친 뒤(외부 워밍업 요청 없음) 1,000명 hot-seats 예매 생성 p95 < 300ms를 독립 3회 모두 달성한다. 달성이 보장되지 않으며 미달이면 결과를 그대로 남긴다.
- 실행 도구: Gatling 러너(`--booking-p95-ms 300`, `verdict.json`).
- 관련 문서: [Gatling 최종 보고](../performance/m5/12-gatling-hot-seats-p95-300ms-report.md), [Gatling 계획](./hot-seats-gatling-p95-300ms.md), [Step 8 최종 보고](../performance/m5/11-step8-final-report.md), [ADR 0025](../adr/0025-booking-seat-copy-cache.md), [ADR 0026](../adr/0026-aot-cache-startup.md).

## 1. 근거와 한계

| 근거 | 수치 | 해석 |
|---|---|---|
| 순수 콜드 B (k6, Scouter OFF) | 중앙값 1,385ms | 회귀 기준(1,500ms)만 통과 |
| 외부 15회 예열 (Gatling) | 전체 p95 350~386ms(D 중앙값 386ms) | **내부 예열의 비교 기준이자 사실상 상한 추정치.** 이미 300ms 미달 |
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

### 3.2 계층형 내부 예열 (합의 후 구현)

- 계층 1(공유 자원 0, 많이 반복): Gateway JWT 검증·필터 파싱·Jackson, core 요청 역직렬화·Bean Validation·`Booking` 도메인 규칙·응답 직렬화, jOOQ 렌더링만, Lettuce 명령 인코딩.
- 계층 2(실제 어댑터, 격리, 적게 반복): 실제 `CreateBookingService.create()` 호출. Redis는 예열 전용 회차 네임스페이스·짧은 TTL·종료 후 삭제, 좌석 사본은 Redis 캐시 경로(ADR 0025), persist는 rollback-only 트랜잭션, 승자·패자(409)를 실제 비율에 가깝게 섞고 동시성 2~4·초당 상한을 둔다. stub 포트로 예열하지 않는다(타입 프로파일 오염으로 deopt).
- Gateway 계층 2는 core ready 뒤 예열 전용 경로로 소수만 호출한다.
- readiness: `ApplicationRunner`에서 예열하고 시간 상한을 넘기면 중단하고 ready로 전환한다.
- 구현 전 확인할 가정: Spring Boot 4.x의 `ApplicationRunner`·readiness 전환 순서(공식 문서), rollback-only persist의 Modulith 이벤트 비발행, `SeatHoldPort` 어댑터가 realtime으로 좌석 이벤트를 흘리지 않는지, JDK 25 AOT 캐시가 기계어를 캐시하지 않는다는 가정(JEP 확인).
- 완료 기준: 폭주 구간 JFR Interpreted 비율이 외부 15회 예열 수준으로 내려가고, 예열 중 DB·Redis 호출 수와 소요 시간이 `env.txt`에 기록된다.

### 3.3 검증

- 예열 포함 기동 3회 p95가 3회 모두 300ms 미만이고 중앙값도 300ms 미만인지 본다. 정합성(중복 판매 0·불일치 0)·오류율·SSE 성공률을 함께 본다.
- 순수 콜드 B를 회귀 기준(1,500ms)으로 함께 잰다. 예열 비활성 설정으로 순수 콜드를 재현할 수 있어야 한다.
- 기동 시간 증가와 예열 상한 동작을 기록한다. 계열을 섞지 않는다.
- 결과는 `docs/performance/m5/13-*.md`로 남기고 허브·로드맵에 연결한다. 미달이면 체크하지 않는다.

## 4. 측정 규칙

- 다른 Gradle 검사와 동시에 실행하지 않는다. 실패·무효 실행은 보존한다.
- Gatling 외부 예열 계열, 컴파일 임계 대조 계열, 내부 예열 계열, 순수 콜드 계열을 별도 표로 기록한다.
- 서비스 코드 변경 전후는 같은 조건에서 비교하고 좋은 회차만 골라 합격시키지 않는다.

## 5. 사용자 결정이 필요한 항목

1. 3.1 대조를 먼저 하고 결과에 따라 3.2 구현 여부를 정하는 순서에 동의하는가.
2. 예열용 쓰기(DB 롤백·Redis 격리)를 이 순서 안에서도 허용하는가(이미 허용됐으나 중단 조건 이후 재확인).
3. 기동 시간 증가의 허용 상한.
