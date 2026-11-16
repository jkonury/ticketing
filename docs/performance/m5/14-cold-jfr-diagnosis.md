# M5 콜드 B 진단: JVM 옵션 실제 적용과 폭주 구간 JFR

> 허브: [M5-2 개선 및 측정 인계](../m5-improvements.md) · 이전: [컴파일 임계 대조](13-compile-threshold-control.md) · 계획: [내부 예열 계획](../../plans/hot-seats-internal-warmup-cold-300ms.md) 3.1.1

## 결론

- 2026-11-16 사용자 선택에 따라 **JVM 옵션 실제 적용 확인 + JFR 콜드 병목 진단**을 수행했다. 서비스 코드·설정과 readiness 동작은 바꾸지 않았다. 내부 예열 구현(3.2)은 시작하지 않았다.
- core·gateway의 기동 로그에서 `CompileThresholdScaling=1.0`·`0.1`의 실제 적용을 확인했다. 두 실행 모두 실제 `CICompilerCount=4`다. 앞선 보고서의 단독 JVM 기본값 12를 부하 실행 JVM의 값으로 사용할 수 없다. 과거 9회 실행의 적용값을 소급 확인한 것은 아니다.
- JFR을 켠 콜드 B 전체 예매 p95는 **1,664ms / 2,006ms**로 모두 300ms 미달이다. 각 설정 1회 진단이므로 설정 간 성능 차이를 확정하거나 기존 JFR OFF 3회 결과에 합치지 않는다.
- 예매 요청 구간 core의 최상위 Java 프레임이 Interpreted인 표본은 **198/373(53.1%) / 159/388(41.0%)**다. 기존 외부 15회 예열 JFR에서는 **0/58**이다. 낮은 컴파일 임계만으로 외부 예열 상태에 도달하지 못했다는 관찰은 유지된다.
- 클래스 로딩 관련 pinning과 DB 연결 획득 대기도 함께 관찰됐다. **JIT·클래스 로딩·초기화·연결 대기의 지연 기여율은 분리하지 못했다.** 내부 예열 무효나 특정 라이브러리가 주원인이라고 결론 내릴 수 없다. 3.2의 중단 상태를 유지한다.

## 실행 조건과 판정

HEAD `dbf4c39`, 서비스 소스 변경 없음. Gatling·힙 2g·1,000명·5,000석·핫 100석·Scouter OFF·관측 ON·추적 0.1·발생기 CPU 0-3 / 앱 4-15·Hikari 풀 10·AOT 없음. 앱을 매번 새로 기동하고 외부 예열은 하지 않았다. JDK Temurin 25.0.5, AMD Ryzen 7 5700G 논리 코어 16. 다른 Gradle 검사·측정과 동시에 실행하지 않았다.

```bash
./scripts/loadtest/run.sh --scenario hot-seats --tool gatling --gatling-heap 2g \
  --runs 1 --observability --no-scouter --jfr --booking-p95-ms 300 \
  --k6-cpus 0-3 --app-cpus 4-15 \
  --jvm-opts '-XX:+PrintFlagsFinal -XX:CompileThresholdScaling=1.0'
# 다음 실행은 CompileThresholdScaling=0.1만 바꿨다.
```

JFR은 러너 기존 설정(`settings=profile`, `jdk.VirtualThreadPinned#threshold=0ms`)을 사용했다. `PrintFlagsFinal` 출력의 `{command line}` 값이 core·gateway 각각 `1.000000`·`0.100000`인지 확인했다. `CICompilerCount=4`는 `{ergonomic}`이며 직접 지정하지 않았다. [JDK 25 java 문서](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html)는 낮은 `CompileThresholdScaling`이 첫 컴파일을 앞당기는 옵션이며 profile 기록에는 추가 비용이 있을 수 있음을 설명한다.

| 계열 | 실행 디렉터리 접두사 | 전체 / 성공 예매 p95 | 정시 준비 | 예상 밖 오류 / 429 | SSE 성공 | 중복 판매 / DB·Redis 불일치 |
|---|---|---|---|---|---|---|
| 콜드 JFR, 1.0 | `20261116-122616` | 1,664 / 1,577ms | 1,000/1,000 | 0 / 0 | 1,000/1,000 | 0 / 0 |
| 콜드 JFR, 0.1 | `20261116-122827` | 2,006 / 1,795ms | 1,000/1,000 | 0 / 0 | 1,000/1,000 | 0 / 0 |
| **기존 비교 자료**, 외부 15회 예열 JFR | `20261115-221926` | 366 / 343ms | 1,000/1,000 | 0 / 0 | 1,000/1,000 | 0 / 0 |

실행 디렉터리는 모두 `build/loadtest/runs/<접두사>-hot-seats/1`이다. p95는 `gatling-summary.json`의 원시 로그 재집계값이다. 새 콜드 실행의 Gatling 콘솔 assertion 값은 각각 1,666·2,007ms이며, 재집계값과 섞지 않는다. 예열 자료는 이전 날짜·커밋의 참고 계열이며 동일 코드에서 예열 효과를 새로 대조한 실험이 아니다.

새 실행 두 번 모두 Gatling 종료 코드 2·러너 종료 코드 1이다. `verdict.json`의 `bookingP95`·`successP95`·`toolExit`가 실패했다. 실패 메시지는 `Gatling 최종 판정 미달`이며, 정합성·준비·SSE·서버 차분·입력 수 일치 검사는 통과했다. 실패 실행을 그대로 보존했다.

## JFR 집계 방법

Gatling 3.16.0 원시 로그에서 1,000개의 `booking_create` 요청에 대해 첫 시작부터 마지막 응답까지를 구하고 JFR의 절대 시각과 맞췄다. 첫 시작은 readiness의 `startAtEpochMs`보다 각각 7·5·6ms 뒤여서 같은 폭주를 가리킨다.

| 계열 | 분석 구간(UTC) | 길이 |
|---|---|---|
| 콜드 1.0 | 2026-11-16 03:27:51.975–03:27:53.828 | 1,853ms |
| 콜드 0.1 | 2026-11-16 03:29:58.609–03:30:00.742 | 2,133ms |
| 외부 15회 예열 | 2026-11-15 13:37:13.418–13:37:13.863 | 445ms |

- 기본 비율: 구간 내 `jdk.ExecutionSample` 전체 표본을 분모로 하고, 최상위 Java 프레임의 `getType()`이 `Interpreted`인 표본을 분자로 한다. Native 프레임은 Java 프레임 API의 구분을 따르고, `jdk.NativeMethodSample`은 이 분모에 섞지 않는다.
- 보조 비율: 모든 Java 프레임 중 Interpreted 프레임 비율. 같은 메서드·호출자가 여러 스택에 반복되므로 CPU 시간 비율로 해석하지 않는다. [RecordedFrame 공식 API](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/consumer/RecordedFrame.html)의 프레임 타입을 사용했다.
- 모든 앱 스레드 표본을 포함한다. 구간에는 결제·백그라운드 작업도 겹친다. 요청 ID별 추적 자료가 아니며, 표본 수·구간 길이가 서로 다르다.
- 대기·pinning 지속 시간은 구간과 겹치는 부분만 합산했다. 여러 스레드가 동시에 기다릴 수 있어 합계는 경과 시간이나 요청 p95와 같지 않다.
- 분석 코드와 집계는 로컬 `build/cold-jfr-diagnosis/`에 보존했다. `window.py`가 `booking-window.json`을 만들고, JDK consumer API를 쓰는 `Summarize.java`·`analyze.py`가 앱별 TSV를 만든다. 대용량 JFR과 함께 git에는 넣지 않는다.

## 관찰

### 인터프리터 표본은 core에 많이 남는다

| 계열 | core 최상위 Interpreted / 표본 | gateway 최상위 Interpreted / 표본 | core 전체 프레임 중 Interpreted | gateway 전체 프레임 중 Interpreted |
|---|---|---|---|---|
| 콜드 1.0 | 198/373 (53.1%) | 8/182 (4.4%) | 43.0% | 9.0% |
| 콜드 0.1 | 159/388 (41.0%) | 7/206 (3.4%) | 28.0% | 7.4% |
| 외부 15회 예열 | 0/58 (0%) | 0/64 (0%) | 1.1% | 6.5% |

core 첫 500ms에서도 50/88·18/47 표본이 최상위 Interpreted였다. 이후 500ms 구간에도 남아 있다. core 표본에는 Spring Security 요청 체인·메서드 인가, Jackson/Kotlin 리플렉션, jOOQ 렌더링, Hibernate 처리가 섞여 있다. 호출 스택에 Security가 포함됐다는 이유로 그 아래 DB·직렬화 처리 시간을 인가 비용으로 돌리지 않는다. Gateway 표본은 주로 Netty/Lettuce와 Reactor 연결 풀 경로를 포함한다.

예열 자료의 0/58은 유한한 표본에서 관찰되지 않았다는 뜻이며, 실제 Interpreted 실행 시간이 0이라는 뜻은 아니다. 0.1에서 비율이 낮아져도 지연은 줄지 않았으므로 **비율 감소만으로 p95 개선을 예측할 수 없다.**

### 클래스 로딩·초기화가 폭주 구간과 겹친다

| core 관찰 | 콜드 1.0 | 콜드 0.1 | 외부 15회 예열 |
|---|---|---|---|
| VirtualThreadPinned 구간 겹침 합계 | 1,177.3ms | 1,294.7ms | 관찰 0건 |
| 그중 ClassLoader 경로가 있는 표본 | 108건, 535.7ms | 122건, 505.3ms | 관찰 0건 |
| JavaMonitorEnter 중 클래스 로딩 경로 | 16건, 261.8ms | 21건, 274.3ms | 관찰 0건 |

pinning 스택에는 `ClassLoader.loadClass`와 Hibernate 결과 초기화 클래스·Jackson `MapSerializer`의 `<clinit>`가 보인다. core의 구간 내 ClassLoadingStatistics는 1.0에서 31,050→31,244, 0.1에서 30,912→31,451로 늘었다. 통계는 약 1초 간격의 일부 구간만 덮으므로 각각 194·539가 폭주 전체 로딩 수는 아니다.

기본 profile은 `jdk.ClassLoad`·`jdk.ClassDefine`을 활성화하지 않는다. 따라서 개별 클래스 로딩·초기화의 전체 횟수와 지속 시간을 이 기록에서 복원할 수 없다. pinning 일부에 나타난 클래스만 예열하면 해결된다고 결론 내리지 않는다.

### 연결 대기는 있으나 연결 생성 비용과 분리되지 않는다

- 서버 차분의 core Hikari 획득 평균은 콜드 1.0 **83.6ms**, 콜드 0.1 **127.8ms**, 기존 외부 예열 **23.7ms**다. 세 자료 모두 획득 타임아웃 0건이다. 이 평균은 본 부하 전체의 차분으로 예매 폭주만의 값은 아니다.
- JFR에는 PostgreSQL 응답 읽기, Hikari 프록시의 쿼리 실행·커밋, Gateway의 `DefaultPooledConnectionProvider` 경로가 보인다. 기존 예열 자료에도 연결 풀 경로가 남는다. 이 스택만으로 새 물리 연결 생성과 기존 연결 사용을 구분할 수 없다.
- ThreadPark의 큰 합계에는 스케줄러·exporter·유휴 작업자 대기가 들어 있다. 전체 합계를 DB 풀 대기로 해석하지 않는다. `HikariPool.getConnection` 하위의 SocketRead도 커넥션 검증 등을 포함할 수 있어 신규 연결 생성 비용으로 단정하지 않는다.
- core의 100ms 이상 Compilation 이벤트 구간 겹침 합계는 3,195·3,452ms, gateway는 4,562·3,745ms다. 여러 컴파일러 스레드의 합이며 요청 중단 시간이 아니다. Gateway의 GarbageCollection 지속 시간 105·196ms도 GC pause와 같은 뜻이 아니다. 컴파일·GC·앱 처리가 겹쳐 있으므로 임계 변경의 비용을 단일 실행으로 분해하지 않는다.

## 판정과 후속 범위

이번 진단은 옵션 실제 적용과 Interpreted 비율이라는 앞선 실험의 공백을 보완했다. **JIT 실행 상태 차이와 클래스 로딩 경합이 모두 관찰되며, 연결 대기도 남아 있다.** 콜드 지연을 JIT 하나로 설명하거나 연결 풀 증설로 해결할 근거는 확보하지 못했다.

추가로 원인을 분리하려면 동일 코드에서 콜드·외부 예열을 다시 대조하고, 별도 진단 계열로 ClassLoad 이벤트 및 연결 생성·획득 완료 시각을 수집해야 한다. 이는 이번에 실행하지 않았다. 내부 예열 구현·예열용 DB/Redis 쓰기·기동 시간 상한 결정은 별도 합의 대상으로 남긴다. p95 300ms 항목은 미완료다.

검증은 실제 부하 2회, JFR 6개(core·gateway × 콜드 2회·기존 예열 1회)의 집계, 문서 검사다. 서비스·Kotlin·web 코드 변경이 없어 전체 단위/통합 테스트·정적 검사·E2E는 실행하지 않았다.
