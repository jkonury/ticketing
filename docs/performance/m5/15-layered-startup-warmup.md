# M5 계층형 내부 예열 구현과 검증

> 허브: [M5-2 개선 및 측정 인계](../m5-improvements.md) · 이전: [콜드 JFR 진단](14-cold-jfr-diagnosis.md) · 계획: [내부 예열 계획](../../plans/hot-seats-internal-warmup-cold-900ms.md) · 결정: [ADR 0029](../../adr/0029-layered-startup-warmup.md)

## 결론

계획 3.2의 기능 구현·회귀 검증은 완료했다. 다만 JFR의 core Interpreted 표본 31.8%는 외부 15회 예열 수준(0/58)에 도달하지 못했고, JFR OFF 독립 3회 p95는 **1,505·1,448·1,242ms(중앙값 1,448ms)**로 모두 300ms 미달이다. 계획 3.2의 JFR 완료 기준과 3.3의 성능 완료 기준은 미충족이며 로드맵 목표를 체크하지 않는다. 순수 콜드 회귀는 사용자 요청으로 생략했다.

## 구현

2026-11-16 사용자 요청으로 계획 3.2를 구현했다. core·gateway의 내부 예열은 기본 비활성이며, 활성화하면 readiness 전에 메모리 연산과 격리된 실제 호출을 수행한다. 앱별 기본 상한은 30초다.

- core: 메모리 10,000회와 실제 예매 200회(승자 10회·패자 190회). 실제 JPA flush 후 DB 롤백, 음수 회차별 Redis 전용 키·생성 즉시 30초 TTL·종료 시 삭제를 사용한다. 일반 좌석 SSE Stream은 생성하지 않는다.
- Gateway: 메모리 10,000회 후 core readiness를 기다리고 실제 Gateway HTTP 경유 8회(승자 1회·패자 7회)를 수행한다. 서버 전용 JWT 역할, 2분 유효 기간·8회 제한으로 경로를 보호한다.
- 러너: `--internal-warmup`으로 별도 측정 계열을 선택하고 core·gateway readiness를 기다린다. 외부 예열·AOT와 동시 사용을 거부하며, 기본 콜드에서는 예열 활성 환경 변수를 명시적으로 덮어쓴다.
- 호출 수: 예열 스레드의 JDBC execute와 Redis 템플릿 명령 호출 수다. Lua 내부 명령·드라이버 재전송은 제외한다. 상한 도달 시 수치는 취소 시점 스냅샷이다.

## 기능 검증

- `ktlintCheck detekt` 통과. 마지막 구현 상태로 전체 테스트를 실행했다.
- 최초 전체 테스트는 `PaymentFlowTests`의 `환불 거절과 미확정은 원 발행 재제출로 같은 키를 재사용한다`가 실패했다(`expected: COMPLETED`, `but was: FAILED`). 기존 조회의 `orderId` 접두사 매칭으로 73이 이전 736548419143104 이벤트와 충돌했다. 숫자 뒤 쉼표까지 비교하도록 두 조회를 수정한 뒤 필터 없는 전체 테스트가 통과했다. 최초 실패 XML은 로컬 `/tmp/ticketing-warmup-initial-payment-failure.xml`에 보존했다.
- 실제 DB 롤백·Redis 키 정리·TTL·SSE 키 격리·이벤트 비발행·음수 회차 입력 거부·서버 역할 보호·비활성 경로·시간 상한을 테스트했다. 모듈 간 의존은 변경하지 않았다.
- 예열 활성 E2E 통과. core `completed`, 6,439ms, 실제 호출 200회·승자 10회·DB 210회·Redis 990회. Gateway `completed`, 15,132ms(core 대기 포함), 경유 8회·DB 9회·Redis 39회. 각 앱 메모리 연산은 10,000회다.
- 셸 구문 검사와 부하 러너 Python 회귀 테스트 통과.

## 성능 측정

Gatling 힙 2g·1,000명·5,000석·핫 100석·Scouter OFF·관측 ON·발생기 CPU 0-3 / 앱 4-15·풀 10을 유지했다. 각 회차 앱을 새로 기동하며 외부 예열·AOT·컴파일 임계 변경은 없다. 다른 Gradle 검사나 JFR 분석과 동시에 측정하지 않는다.

```bash
./scripts/loadtest/run.sh --scenario hot-seats --tool gatling --gatling-heap 2g \
  --runs 1 --observability --no-scouter --internal-warmup --jfr \
  --booking-p95-ms 300 --k6-cpus 0-3 --app-cpus 4-15
# 성능 판정은 --jfr을 빼고 --runs 3으로 별도 실행한다.
# 순수 콜드 회귀는 사용자 요청으로 생략했다.
```

| 계열 | 실행 디렉터리 접두사 / 회차 | 전체 / 성공 예매 p95 | 판정 |
|---|---|---|---|
| 내부 예열 JFR 진단 | `20261116-130429` / 1 | 1,130 / 1,030ms | 300ms 미달 |
| 내부 예열 JFR OFF | `20261116-130636` / 1 | 1,505 / 1,184ms | 300ms 미달 |
| 내부 예열 JFR OFF | `20261116-130636` / 2 | 1,448 / 1,354ms | 300ms 미달 |
| 내부 예열 JFR OFF | `20261116-130636` / 3 | 1,242 / 1,119ms | 300ms 미달 |

JFR 진단은 정시 준비 1,000/1,000, 예상 밖 오류·429 0건, SSE 1,000/1,000, 중복 판매·DB/Redis 불일치 0건이다. 예열은 core 6,534ms·Gateway 15,319ms(core 대기 포함)에 완료했고 호출 수는 E2E와 같다. 예열 후 Redis 전용 키와 DB 음수 회차 예매가 모두 0건임을 직접 확인했다. 원시 로그 재집계 p95를 표에 사용한다(Gatling 콘솔은 1,131ms).

JFR OFF 3회도 모두 정시 준비 1,000/1,000, 예상 밖 오류·429 0건, SSE 1,000/1,000, 중복 판매·DB/Redis 불일치 0건이다. 전체·성공 예매 p95와 도구 종료 코드만 판정 미달이다. Gatling 종료 코드는 각 2, 러너 종료 코드는 1이며 실패 결과도 보존했다. 콘솔 p95는 1,463·1,449·1,242ms로 원시 로그 재집계와 구분한다.

| JFR OFF 회차 | core 예열 시간 | Gateway 예열 시간(core 대기 포함) |
|---|---|---|
| 1 | 6,545ms | 15,247ms |
| 2 | 6,510ms | 14,899ms |
| 3 | 6,528ms | 15,105ms |

각 회차 두 앱 모두 `completed`다. core 메모리 10,000회·실제 200회·승자 10회·DB 210회·Redis 990회, Gateway 메모리 10,000회·경유 8회·DB 9회·Redis 39회로 동일하다. `env.txt`에 결과가 남는다. `verdict.json`의 기존 `warmup=false`는 외부 예열 비활성을 뜻하며 내부 예열 여부는 `env.txt`의 `internal-warmup: true`로 구분한다.

실행 디렉터리는 `build/loadtest/runs/<접두사>-hot-seats/<회차>`다. 종료 뒤에도 예열 전용 Redis 키·음수 회차 예매는 모두 0건이었다. E2E에서 시작한 fake-pg 컨테이너는 측정 중 포트 충돌을 피하도록 정지했다가 사용자 승인에 따라 복원했다.

## JFR 진단과 한계

기존 분석과 동일하게 Gatling 원시 로그의 예매 요청 시작~마지막 응답 구간(UTC 04:06:04.513~04:06:05.768, 1,255ms)을 사용했다. 실행 구간 내 모든 앱 스레드의 `jdk.ExecutionSample`을 분모로 하며 요청별 추적이나 지연 기여율 측정은 아니다. 로컬 `build/cold-jfr-diagnosis/`의 `window.py`·`Summarize.java`를 재사용하고 새 결과는 `internal-warmup-core.tsv`·`internal-warmup-gateway.tsv`로 저장해 과거 집계를 보존했다.

| 앱 | 최상위 Java 프레임이 Interpreted인 표본 | 비율 |
|---|---|---|
| core | 95/299 | 31.8% |
| Gateway | 14/212 | 6.6% |

기존 순수 콜드 core 53.1%·41.0%, 외부 15회 예열 0/58과 비교해도 외부 예열 수준에 도달하지 못했다. 과거 날짜·코드·표본 수가 다른 참고 계열이므로 이번 예열의 개선율이나 인과로 환산하지 않는다. core에는 클래스 로딩 관련 pinning 합계 280.7ms(여러 스레드 합산)와 인터프리터 표본이 함께 남았다. 직접 유스케이스 호출과 소수의 내부 HTTP 호출만으로 정상 예매 HTTP 경로 전체가 충분히 준비됐다고 볼 수 없다. 특정 라이브러리나 JIT가 p95의 주원인이라는 결론은 내리지 않는다.

순수 콜드 비활성 설정의 성능 회귀와 기동 시간 증가량은 이번에 측정하지 않았다. 상한 취소 동작은 자동 테스트에서 확인했으며 실제 앱을 상한에 걸리게 하는 추가 기동 실험은 수행하지 않았다. 무근거한 반복 수·동시성·풀 변경은 하지 않았다.

## 최종 검사와 로그

- 전체 테스트: `build/run-tests/gradle-test-out.txt`(수정 후 통과).
- 최종 `ktlintCheck detekt`: `build/check-summary/gradle-out.txt`(통과).
- 예열 활성 E2E: `build/e2e/logs/`, 요약 로그 `/tmp/ticketing-command.YhsK4YQa`(통과).
- 셸·Python 회귀: `/tmp/ticketing-command.rlf6bZoa`(통과).
- JFR 진단·성능 3회 러너: `/tmp/ticketing-command.4GRjoVri`·`/tmp/ticketing-command.z1DghjKN`(성능 기준 미달).
- 문서 링크·경로 검사와 `git diff --check` 통과. web 변경과 모듈 간 의존 변경이 없어 web 검사·Modulith 다이어그램 재생성은 수행하지 않았다.
